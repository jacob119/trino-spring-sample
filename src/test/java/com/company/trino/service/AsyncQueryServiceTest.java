package com.company.trino.service;

import com.company.trino.config.TrinoProperties;
import com.company.trino.model.AsyncQueryAccepted;
import com.company.trino.model.AsyncQueryStatus;
import com.company.trino.model.QueryRequest;
import com.company.trino.model.QueryResult;
import com.company.trino.model.QueryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncQueryServiceTest {

    @Mock private TrinoQueryService queryService;
    @Mock private TrinoProperties props;

    // 동기 실행으로 CompletableFuture 없이 결정적 테스트
    private final Executor syncExecutor = Runnable::run;

    private AsyncQueryService asyncService;

    @BeforeEach
    void setUp() {
        TrinoProperties.AsyncConfig asyncConfig = new TrinoProperties.AsyncConfig();
        asyncConfig.setResultTtlSeconds(600);
        when(props.getAsync()).thenReturn(asyncConfig);

        asyncService = new AsyncQueryService(queryService, syncExecutor, props);
    }

    @Test
    @DisplayName("제출 즉시 FINISHED 상태 (동기 실행)")
    void submit_syncExecution_statusIsFinished() {
        QueryResult result = new QueryResult("alice", List.of("id"), List.of(), 0, 10L);
        when(queryService.executeQuery(anyString(), any())).thenReturn(result);
        doNothing().when(queryService).validateSessionUser(anyString());

        AsyncQueryAccepted accepted = asyncService.submit("alice",
                new QueryRequest("SELECT 1", null), "http://localhost:8083");

        assertThat(accepted.queryId()).isNotBlank();
        assertThat(accepted.statusUrl()).contains(accepted.queryId());
        assertThat(accepted.resultsUrl()).contains(accepted.queryId());

        Optional<AsyncQueryStatus> status = asyncService.getStatus(accepted.queryId());
        assertThat(status).isPresent();
        assertThat(status.get().status()).isEqualTo(QueryStatus.FINISHED);
        assertThat(status.get().sessionUser()).isEqualTo("alice");
        assertThat(status.get().rowCount()).isEqualTo(0);
        assertThat(status.get().completedAt()).isNotNull();
        assertThat(status.get().elapsedMs()).isNotNull();
    }

    @Test
    @DisplayName("쿼리 실패 시 FAILED 상태와 error 메시지")
    void submit_queryFails_statusIsFailed() {
        when(queryService.executeQuery(anyString(), any()))
                .thenThrow(new TrinoQueryService.TrinoQueryException("failed", new RuntimeException()));
        doNothing().when(queryService).validateSessionUser(anyString());

        AsyncQueryAccepted accepted = asyncService.submit("alice",
                new QueryRequest("SELECT bad", null), "http://localhost:8083");

        Optional<AsyncQueryStatus> status = asyncService.getStatus(accepted.queryId());
        assertThat(status).isPresent();
        assertThat(status.get().status()).isEqualTo(QueryStatus.FAILED);
        assertThat(status.get().error()).isNotBlank();
        assertThat(status.get().rowCount()).isNull();
    }

    @Test
    @DisplayName("없는 queryId 조회 시 empty 반환")
    void getStatus_unknownId_returnsEmpty() {
        assertThat(asyncService.getStatus("nonexistent-id")).isEmpty();
    }

    @Test
    @DisplayName("FINISHED 상태에서 결과 조회 성공")
    void getResult_finishedJob_returnsResult() {
        QueryResult expected = new QueryResult("alice", List.of("id"), List.of(
                java.util.Map.of("id", 1L)
        ), 1, 10L);
        when(queryService.executeQuery(anyString(), any())).thenReturn(expected);
        doNothing().when(queryService).validateSessionUser(anyString());

        AsyncQueryAccepted accepted = asyncService.submit("alice",
                new QueryRequest("SELECT 1", null), "http://localhost:8083");

        Optional<QueryResult> result = asyncService.getResult(accepted.queryId());
        assertThat(result).isPresent();
        assertThat(result.get().sessionUser()).isEqualTo("alice");
        assertThat(result.get().rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FAILED 상태에서 결과 조회 시 empty")
    void getResult_failedJob_returnsEmpty() {
        when(queryService.executeQuery(anyString(), any()))
                .thenThrow(new TrinoQueryService.TrinoQueryException("failed", new RuntimeException()));
        doNothing().when(queryService).validateSessionUser(anyString());

        AsyncQueryAccepted accepted = asyncService.submit("alice",
                new QueryRequest("SELECT bad", null), "http://localhost:8083");

        assertThat(asyncService.getResult(accepted.queryId())).isEmpty();
    }

    @Test
    @DisplayName("없는 queryId에 대한 결과 조회 시 empty")
    void getResult_unknownId_returnsEmpty() {
        assertThat(asyncService.getResult("nonexistent-id")).isEmpty();
    }

    @Test
    @DisplayName("만료된 잡은 eviction 후 조회 안됨")
    void evictExpiredJobs_removesExpiredTerminalJobs() throws Exception {
        // TTL이 0초인 서비스 생성 → 즉시 만료
        TrinoProperties.AsyncConfig zerTtl = new TrinoProperties.AsyncConfig();
        zerTtl.setResultTtlSeconds(0);
        when(props.getAsync()).thenReturn(zerTtl);
        AsyncQueryService shortTtlService = new AsyncQueryService(queryService, syncExecutor, props);

        QueryResult result = new QueryResult("alice", List.of(), List.of(), 0, 1L);
        when(queryService.executeQuery(anyString(), any())).thenReturn(result);
        doNothing().when(queryService).validateSessionUser(anyString());

        AsyncQueryAccepted accepted = shortTtlService.submit("alice",
                new QueryRequest("SELECT 1", null), "http://localhost:8083");

        // FINISHED 상태 확인
        assertThat(shortTtlService.getStatus(accepted.queryId())).isPresent();

        // 만료 처리
        shortTtlService.evictExpiredJobs();

        // 삭제 확인
        assertThat(shortTtlService.getStatus(accepted.queryId())).isEmpty();
    }
}
