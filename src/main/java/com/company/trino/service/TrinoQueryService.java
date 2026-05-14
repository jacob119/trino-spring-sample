package com.company.trino.service;

import com.company.trino.config.TrinoProperties;
import com.company.trino.exception.ImpersonationDeniedException;
import com.company.trino.model.QueryRequest;
import com.company.trino.model.QueryResult;
import com.company.trino.pool.TrinoConnectionPool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trino 쿼리 실행 서비스.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Impersonation 흐름                                             │
 * │                                                                 │
 * │  클라이언트                  Spring App              Trino      │
 * │  ─────────                  ──────────              ─────      │
 * │  X-Session-User: alice  →   sessionUser = alice              │
 * │                             LDAP 계정(svc-app)으로 인증         │
 * │                             ────────────────────────────────►  │
 * │                             Authorization: Basic svc-app:pass  │
 * │                             X-Trino-User: alice               │
 * │                                                                 │
 * │  Trino는 svc-app이 alice로 impersonation 가능한지               │
 * │  rules.json을 확인한 후 허용하면 alice 권한으로 쿼리를 실행한다.│
 * └─────────────────────────────────────────────────────────────────┘
 */
@Service
public class TrinoQueryService {

    private static final Logger log = LoggerFactory.getLogger(TrinoQueryService.class);

    private final TrinoConnectionPool connectionPool;
    private final TrinoProperties props;
    private final ObjectMapper objectMapper;

    public TrinoQueryService(TrinoConnectionPool connectionPool,
                              TrinoProperties props,
                              ObjectMapper objectMapper) {
        this.connectionPool = connectionPool;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 지정된 sessionUser로 impersonation하여 SQL을 실행하고 결과를 반환한다.
     * 전체 결과를 메모리에 담으므로 대용량 쿼리에는 streamQuery를 사용한다.
     *
     * @param sessionUser 실제 쿼리를 실행할 사용자 ID
     * @param request     실행할 SQL과 최대 행 수 (null이면 1000)
     * @throws IllegalArgumentException      sessionUser가 null이거나 비어있을 때
     * @throws ImpersonationDeniedException  allowedPattern에 맞지 않는 sessionUser일 때
     * @throws TrinoQueryException           Trino 연결 또는 쿼리 실패 시
     */
    public QueryResult executeQuery(String sessionUser, QueryRequest request) {
        validateImpersonation(sessionUser);

        int maxRows = request.maxRows() != null ? request.maxRows() : 1000;
        long start = System.currentTimeMillis();
        DataSource ds = connectionPool.getDataSource(sessionUser);

        log.debug("[Query] 실행 시작: sessionUser={}, sql={}", sessionUser, request.sql());

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // setMaxRows()는 클라이언트 측(JDBC 드라이버)에서 행 수를 제한한다.
            // Trino 서버는 이 값을 인식하지 못하므로, 대량 결과에 대해서는 LIMIT 절을 함께 사용하길 권장한다.
            stmt.setMaxRows(maxRows);
            stmt.setQueryTimeout(props.getPool().getQueryTimeout());

            try (ResultSet rs = stmt.executeQuery(request.sql())) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<String> columns = extractColumns(meta, colCount);
                List<Map<String, Object>> rows = extractRows(rs, columns, colCount, maxRows);

                long elapsed = System.currentTimeMillis() - start;
                log.info("[Query] 완료: sessionUser={}, rows={}, elapsed={}ms",
                        sessionUser, rows.size(), elapsed);

                return new QueryResult(sessionUser, columns, rows, rows.size(), elapsed);
            }

        } catch (SQLException e) {
            // 상세 SQL 오류는 서버 로그에만 기록 — 클라이언트에 내부 메시지 노출 금지
            log.error("[Query] 실패: sessionUser={}", sessionUser, e);
            throw new TrinoQueryException("query execution failed", e);
        }
    }

    /**
     * 결과를 NDJSON으로 스트리밍한다. 전체 결과를 메모리에 담지 않아 OOM 위험이 없다.
     *
     * 출력 형식 (각 줄은 JSON 오브젝트 + \n):
     *   {"type":"meta","sessionUser":"alice","columns":["id","name"]}
     *   {"type":"row","data":{"id":1,"name":"Alice"}}
     *   ...
     *   {"type":"done","rowCount":N,"elapsedMs":M}
     *   또는 오류 시:
     *   {"type":"error","message":"query execution failed"}
     *
     * 주의: 이 메서드가 호출되는 시점에 HTTP 헤더가 이미 커밋되었으므로,
     * SQL 오류 발생 시 HTTP 상태 코드를 변경할 수 없다. 오류 내용은 NDJSON 라인으로 전달된다.
     * 헤더 커밋 전에 validateSessionUser()를 컨트롤러에서 호출해 인증 오류는 사전 차단한다.
     *
     * @param sessionUser 실제 쿼리를 실행할 사용자 ID (컨트롤러에서 사전 검증됨)
     * @param request     실행할 SQL과 최대 행 수 (null 또는 0이면 무제한)
     * @param out         HTTP 응답 OutputStream
     */
    public void streamQuery(String sessionUser, QueryRequest request, OutputStream out)
            throws IOException {
        long start = System.currentTimeMillis();
        DataSource ds = connectionPool.getDataSource(sessionUser);

        log.debug("[Stream] 시작: sessionUser={}, sql={}", sessionUser, request.sql());

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            int maxRows = request.maxRows() != null ? request.maxRows() : 0;
            if (maxRows > 0) stmt.setMaxRows(maxRows);
            stmt.setQueryTimeout(props.getPool().getQueryTimeout());

            try (ResultSet rs = stmt.executeQuery(request.sql())) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = extractColumns(meta, colCount);

                writeLine(out, Map.of("type", "meta", "sessionUser", sessionUser, "columns", columns));

                int rowCount = 0;
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>(colCount * 2);
                    for (int i = 1; i <= colCount; i++) {
                        row.put(columns.get(i - 1), extractValue(rs, i));
                    }
                    writeLine(out, Map.of("type", "row", "data", row));
                    rowCount++;
                }

                long elapsed = System.currentTimeMillis() - start;
                writeLine(out, Map.of("type", "done", "rowCount", rowCount, "elapsedMs", elapsed));
                log.info("[Stream] 완료: sessionUser={}, rows={}, elapsed={}ms",
                        sessionUser, rowCount, elapsed);
            }

        } catch (SQLException e) {
            log.error("[Stream] 실패: sessionUser={}", sessionUser, e);
            try {
                writeLine(out, Map.of("type", "error", "message", "query execution failed"));
            } catch (IOException ignore) {
                // 클라이언트 연결 끊김
            }
        }
    }

    /**
     * sessionUser에 대한 impersonation 허용 여부를 사전에 검증한다.
     * 스트리밍/비동기 컨트롤러에서 HTTP 헤더 커밋 전에 호출해야 한다.
     *
     * @throws IllegalArgumentException     sessionUser가 null이거나 비어있을 때
     * @throws ImpersonationDeniedException allowedPattern에 맞지 않을 때
     */
    public void validateSessionUser(String sessionUser) {
        validateImpersonation(sessionUser);
    }

    /**
     * Trino에서 current_user를 조회하여 impersonation이 올바르게 적용됐는지 확인한다.
     *
     * @param sessionUser impersonation 대상 사용자 ID
     * @return Trino가 인식하는 현재 사용자. 조회 실패 시 null.
     */
    public String getCurrentUser(String sessionUser) {
        validateImpersonation(sessionUser);
        DataSource ds = connectionPool.getDataSource(sessionUser);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_user")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            log.error("[Query] current_user 조회 실패: sessionUser={}", sessionUser, e);
            throw new TrinoQueryException("current_user query failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 유틸
    // ─────────────────────────────────────────────────────────────

    /**
     * impersonation 허용 여부를 사전에 검증한다.
     *
     * impersonation.enabled=false이면 모든 검증을 건너뛴다.
     * allowedPattern은 정규식으로 평가되며, 매칭 실패 시 Trino로 요청이 가기 전에 차단한다.
     * 이 검증은 애플리케이션 레벨 보호이며, Trino rules.json이 최종 권한 결정자이다.
     */
    private void validateImpersonation(String sessionUser) {
        if (!props.getImpersonation().isEnabled()) return;
        if (sessionUser == null || sessionUser.isBlank()) {
            throw new IllegalArgumentException("sessionUser는 필수입니다.");
        }
        if (!sessionUser.matches(props.getImpersonation().getAllowedPattern())) {
            throw new ImpersonationDeniedException(sessionUser);
        }
    }

    private List<String> extractColumns(ResultSetMetaData meta, int colCount) throws SQLException {
        List<String> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnName(i));
        }
        return columns;
    }

    // LinkedHashMap을 사용하여 컬럼 순서를 ResultSet과 동일하게 유지한다.
    private List<Map<String, Object>> extractRows(ResultSet rs, List<String> columns,
                                                   int colCount, int maxRowsHint)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(maxRowsHint, 1000));
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(colCount * 2);
            for (int i = 1; i <= colCount; i++) {
                row.put(columns.get(i - 1), extractValue(rs, i));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * ResultSet에서 값을 추출한다.
     * ARRAY 타입은 java.sql.Array.getArray()로 Java 배열로 변환하여 JSON 직렬화를 돕는다.
     * 그 외 복잡한 Trino 타입(ROW 등)은 toString()으로 폴백한다.
     */
    private static Object extractValue(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null) return null;
        if (value instanceof java.sql.Array array) {
            try {
                return array.getArray();
            } catch (SQLException ignored) {
                return value.toString();
            }
        }
        // ROW, MAP 등 Trino 고유 타입은 toString()으로 폴백
        String typeName = value.getClass().getName();
        if (typeName.startsWith("io.trino.")) {
            return value.toString();
        }
        return value;
    }

    private void writeLine(OutputStream out, Object value) throws IOException {
        out.write(objectMapper.writeValueAsBytes(value));
        out.write('\n');
        out.flush();
    }

    /**
     * Trino 연결 또는 쿼리 실행 실패 시 발생하는 예외.
     * 원인 SQLException을 감싸서 상위 레이어에 전달한다.
     * 상세 메시지는 서버 로그에만 기록하고, GlobalExceptionHandler에서 클라이언트에 일반 메시지를 반환한다.
     */
    public static class TrinoQueryException extends RuntimeException {
        public TrinoQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
