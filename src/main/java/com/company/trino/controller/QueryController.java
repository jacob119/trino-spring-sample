package com.company.trino.controller;

import com.company.trino.model.QueryRequest;
import com.company.trino.model.QueryResult;
import com.company.trino.pool.TrinoConnectionPool;
import com.company.trino.service.TrinoQueryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Trino 쿼리 실행 REST API 컨트롤러.
 *
 * 엔드포인트 목록:
 *   POST /api/query       - SQL 쿼리 실행
 *   GET  /api/whoami      - 현재 Trino 사용자 확인 (impersonation 검증)
 *   GET  /api/pool/stats  - 커넥션 풀 모니터링
 *
 * 인증 방식:
 *   클라이언트는 X-Session-User 헤더로 실행 주체(sessionUser)를 지정한다.
 *   Trino에 대한 실제 인증은 application.yml의 ldap.user/password가 담당한다.
 *
 * 예외 처리:
 *   GlobalExceptionHandler가 모든 예외를 처리하므로 이 컨트롤러에는 @ExceptionHandler를 두지 않는다.
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final TrinoQueryService queryService;
    private final TrinoConnectionPool connectionPool;

    public QueryController(TrinoQueryService queryService, TrinoConnectionPool connectionPool) {
        this.queryService = queryService;
        this.connectionPool = connectionPool;
    }

    /**
     * SQL 쿼리를 실행하고 결과를 반환한다.
     *
     * X-Session-User 헤더에 지정된 사용자로 impersonation하여 쿼리를 실행한다.
     * maxRows를 생략하면 기본값 1000행이 적용된다.
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResult> executeQuery(
            @RequestHeader("X-Session-User") String sessionUser,
            @Valid @RequestBody QueryRequest request) {

        log.info("[API] 쿼리 요청: sessionUser={}", sessionUser);
        QueryResult result = queryService.executeQuery(sessionUser, request);
        return ResponseEntity.ok(result);
    }

    /**
     * Trino에서 current_user를 조회하여 impersonation이 올바르게 적용됐는지 확인한다.
     *
     * sessionUser와 trinoCurrentUser가 동일하면 impersonation이 정상 동작하는 것이다.
     */
    @GetMapping("/whoami")
    public ResponseEntity<Map<String, String>> whoAmI(
            @RequestHeader("X-Session-User") String sessionUser) {

        String currentUser = queryService.getCurrentUser(sessionUser);
        return ResponseEntity.ok(Map.of(
                "sessionUser", sessionUser,
                "trinoCurrentUser", currentUser != null ? currentUser : "unknown"
        ));
    }

    /**
     * 현재 활성화된 모든 사용자별 HikariCP 풀의 통계를 반환한다.
     *
     * 주의: 이 엔드포인트는 모든 활성 사용자 목록을 노출한다.
     * 운영 환경에서는 Spring Security나 IP 화이트리스트로 접근을 제한해야 한다.
     */
    @GetMapping("/pool/stats")
    public ResponseEntity<Map<String, TrinoConnectionPool.PoolStats>> poolStats() {
        return ResponseEntity.ok(connectionPool.getStats());
    }
}
