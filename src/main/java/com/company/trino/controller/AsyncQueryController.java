package com.company.trino.controller;

import com.company.trino.model.AsyncQueryAccepted;
import com.company.trino.model.AsyncQueryStatus;
import com.company.trino.model.QueryRequest;
import com.company.trino.model.QueryResult;
import com.company.trino.model.QueryStatus;
import com.company.trino.service.AsyncQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 비동기 쿼리 실행 REST API 컨트롤러 (Submit-and-Poll 패턴).
 *
 * ─────────────────────────────────────────────────────────────────
 * 사용 예시 (curl)
 * ─────────────────────────────────────────────────────────────────
 *
 * # 1. 쿼리 제출 → 202 + queryId
 * curl -s -X POST http://localhost:8083/api/query/async \
 *      -H "X-Session-User: alice" \
 *      -H "Content-Type: application/json" \
 *      -d '{"sql":"SELECT * FROM tpch.tiny.orders LIMIT 1000"}'
 *
 * # 2. 상태 폴링
 * curl -s http://localhost:8083/api/query/async/{queryId}
 *
 * # 3. 완료 후 결과 조회
 * curl -s http://localhost:8083/api/query/async/{queryId}/results
 * ─────────────────────────────────────────────────────────────────
 *
 * 엔드포인트:
 *   POST /api/query/async           - 쿼리 제출 (202 즉시 반환)
 *   GET  /api/query/async/{id}      - 실행 상태 폴링
 *   GET  /api/query/async/{id}/results - 결과 조회 (FINISHED 시에만)
 */
@RestController
@RequestMapping("/api/query/async")
public class AsyncQueryController {

    private static final Logger log = LoggerFactory.getLogger(AsyncQueryController.class);

    private final AsyncQueryService asyncQueryService;

    public AsyncQueryController(AsyncQueryService asyncQueryService) {
        this.asyncQueryService = asyncQueryService;
    }

    /**
     * 쿼리를 비동기로 제출한다.
     *
     * sessionUser 검증은 즉시 수행되며, 실패 시 400/403을 반환한다.
     * 검증 통과 후 쿼리는 백그라운드 스레드에서 실행되고, 클라이언트는 202를 즉시 받는다.
     *
     * @return 202 Accepted + { queryId, statusUrl, resultsUrl }
     */
    @PostMapping
    public ResponseEntity<AsyncQueryAccepted> submitQuery(
            @RequestHeader("X-Session-User") String sessionUser,
            @Valid @RequestBody QueryRequest request,
            HttpServletRequest httpRequest) {

        log.info("[API] 비동기 쿼리 제출: sessionUser={}", sessionUser);
        String baseUrl = extractBaseUrl(httpRequest);
        AsyncQueryAccepted accepted = asyncQueryService.submit(sessionUser, request, baseUrl);
        return ResponseEntity.accepted().body(accepted);
    }

    /**
     * 비동기 쿼리의 현재 실행 상태를 반환한다.
     *
     * RUNNING  → 계속 폴링
     * FINISHED → resultsUrl에서 결과 조회
     * FAILED   → error 필드에 오류 메시지
     *
     * @return 200 OK + AsyncQueryStatus, 또는 404 Not Found
     */
    @GetMapping("/{id}")
    public ResponseEntity<AsyncQueryStatus> getStatus(@PathVariable String id) {
        return asyncQueryService.getStatus(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * FINISHED 상태의 쿼리 결과를 반환한다.
     *
     * RUNNING  → 202 Accepted (아직 준비되지 않음, 계속 폴링)
     * FAILED   → 500 Internal Server Error
     * FINISHED → 200 OK + QueryResult
     * 없음     → 404 Not Found (TTL 만료 또는 잘못된 ID)
     */
    @GetMapping("/{id}/results")
    public ResponseEntity<QueryResult> getResults(@PathVariable String id) {
        var statusOpt = asyncQueryService.getStatus(id);
        if (statusOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        QueryStatus status = statusOpt.get().status();
        if (status == QueryStatus.RUNNING) {
            return ResponseEntity.accepted().build(); // 202 — 아직 준비 안됨
        }
        if (status == QueryStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        return asyncQueryService.getResult(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 요청 URL에서 scheme://host:port 부분만 추출한다. */
    private String extractBaseUrl(HttpServletRequest req) {
        StringBuffer url = req.getRequestURL();
        String uri = req.getRequestURI();
        return url.substring(0, url.length() - uri.length());
    }
}
