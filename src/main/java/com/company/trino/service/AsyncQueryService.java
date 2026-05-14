package com.company.trino.service;

import com.company.trino.config.TrinoProperties;
import com.company.trino.model.AsyncQueryAccepted;
import com.company.trino.model.AsyncQueryStatus;
import com.company.trino.model.QueryRequest;
import com.company.trino.model.QueryResult;
import com.company.trino.model.QueryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 비동기 쿼리 실행 서비스.
 *
 * ────────────────────────────────────────────────────────────
 * 비동기 쿼리 흐름 (Submit-and-Poll 패턴)
 * ────────────────────────────────────────────────────────────
 *
 *  1. POST /api/query/async
 *     → 즉시 202 Accepted + { queryId, statusUrl, resultsUrl }
 *     → 백그라운드 스레드에서 Trino 쿼리 시작
 *
 *  2. GET /api/query/async/{id}  (폴링)
 *     → { status: RUNNING }   : 아직 실행 중
 *     → { status: FINISHED }  : 완료, resultsUrl 조회 가능
 *     → { status: FAILED }    : 실패, error 메시지 확인
 *
 *  3. GET /api/query/async/{id}/results
 *     → QueryResult (FINISHED 상태일 때만)
 *
 * 잡 결과는 resultTtlSeconds 이후 자동 삭제된다.
 *
 * ────────────────────────────────────────────────────────────
 * 스레드 안전성 설계
 * ────────────────────────────────────────────────────────────
 * QueryJob.status 필드가 volatile로 선언되어 있고,
 * finish()/fail()에서 result/error를 먼저 쓴 뒤 status를 마지막으로 쓴다.
 * 이 순서가 happens-before를 보장하므로 별도 synchronized 없이
 * 읽기 쪽에서 status=FINISHED를 보는 순간 result도 안전하게 읽힌다.
 */
@Service
public class AsyncQueryService {

    private static final Logger log = LoggerFactory.getLogger(AsyncQueryService.class);

    private final TrinoQueryService queryService;
    private final Executor queryExecutor;
    private final long resultTtlMs;

    // queryId → QueryJob 매핑. ConcurrentHashMap으로 스레드 안전 보장.
    private final ConcurrentHashMap<String, QueryJob> jobs = new ConcurrentHashMap<>();

    public AsyncQueryService(TrinoQueryService queryService,
                              @Qualifier("queryExecutor") Executor queryExecutor,
                              TrinoProperties props) {
        this.queryService = queryService;
        this.queryExecutor = queryExecutor;
        this.resultTtlMs = props.getAsync().getResultTtlSeconds() * 1000L;
    }

    /**
     * 쿼리를 비동기로 제출한다.
     *
     * sessionUser 검증은 HTTP 헤더 커밋 전(202 반환 전)에 수행되므로
     * 잘못된 sessionUser는 4xx 오류로 즉시 거부된다.
     *
     * @param sessionUser 실행 주체
     * @param request     SQL 및 옵션
     * @param baseUrl     상태/결과 URL 생성에 사용할 서버 기본 URL
     */
    public AsyncQueryAccepted submit(String sessionUser, QueryRequest request, String baseUrl) {
        queryService.validateSessionUser(sessionUser);

        String queryId = UUID.randomUUID().toString();
        QueryJob job = new QueryJob(queryId, sessionUser, System.currentTimeMillis());
        jobs.put(queryId, job);

        queryExecutor.execute(() -> {
            try {
                QueryResult result = queryService.executeQuery(sessionUser, request);
                job.finish(result);
                log.info("[Async] 완료: queryId={}, sessionUser={}, rows={}",
                        queryId, sessionUser, result.rowCount());
            } catch (Exception e) {
                log.error("[Async] 실패: queryId={}, sessionUser={}", queryId, sessionUser, e);
                job.fail("query execution failed");
            }
        });

        return new AsyncQueryAccepted(
                queryId,
                baseUrl + "/api/query/async/" + queryId,
                baseUrl + "/api/query/async/" + queryId + "/results"
        );
    }

    /** queryId에 해당하는 잡의 현재 상태를 반환한다. 없으면 empty. */
    public Optional<AsyncQueryStatus> getStatus(String queryId) {
        QueryJob job = jobs.get(queryId);
        if (job == null) return Optional.empty();
        return Optional.of(job.toStatus());
    }

    /**
     * FINISHED 상태인 잡의 결과를 반환한다.
     * RUNNING이거나 FAILED이면 empty.
     */
    public Optional<QueryResult> getResult(String queryId) {
        QueryJob job = jobs.get(queryId);
        if (job == null || job.status() != QueryStatus.FINISHED) return Optional.empty();
        return Optional.ofNullable(job.result());
    }

    /**
     * 만료된 완료/실패 잡을 주기적으로 제거한다.
     * 완료 후 resultTtlMs가 지난 잡만 삭제하므로 진행 중인 잡에 영향을 주지 않는다.
     */
    @Scheduled(fixedDelay = 60_000)
    void evictExpiredJobs() {
        long cutoff = System.currentTimeMillis() - resultTtlMs;
        int removed = 0;
        for (var entry : jobs.entrySet()) {
            QueryJob job = entry.getValue();
            if (job.isTerminal() && job.completedAt() != null && job.completedAt() < cutoff) {
                jobs.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[Async] 만료된 잡 제거: {}건", removed);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 잡 상태 컨테이너
    // ─────────────────────────────────────────────────────────────

    static final class QueryJob {
        private final String queryId;
        private final String sessionUser;
        private final long submittedAt;

        // volatile 필드 쓰기 순서가 happens-before를 보장한다:
        //   result/error를 먼저 쓴 뒤 status를 마지막으로 쓴다.
        //   읽기 쪽에서 status=FINISHED를 보는 순간 result도 최신값이 보인다.
        private volatile QueryResult result;
        private volatile String error;
        private volatile Long completedAt;
        private volatile QueryStatus status = QueryStatus.RUNNING;

        QueryJob(String queryId, String sessionUser, long submittedAt) {
            this.queryId = queryId;
            this.sessionUser = sessionUser;
            this.submittedAt = submittedAt;
        }

        void finish(QueryResult r) {
            this.result = r;
            this.completedAt = System.currentTimeMillis();
            this.status = QueryStatus.FINISHED; // status는 마지막에 써서 가시성 보장
        }

        void fail(String message) {
            this.error = message;
            this.completedAt = System.currentTimeMillis();
            this.status = QueryStatus.FAILED;
        }

        QueryStatus status() { return status; }
        QueryResult result() { return result; }
        Long completedAt() { return completedAt; }
        boolean isTerminal() { return status != QueryStatus.RUNNING; }

        AsyncQueryStatus toStatus() {
            Long elapsed = completedAt != null ? completedAt - submittedAt : null;
            Integer rowCount = result != null ? result.rowCount() : null;
            return new AsyncQueryStatus(
                    queryId, status, sessionUser, submittedAt,
                    completedAt, elapsed, rowCount, error
            );
        }
    }
}
