package com.company.trino.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GET /api/query/async/{id} 폴링 응답.
 *
 * status=RUNNING  → completedAt, elapsedMs, rowCount, error는 null
 * status=FINISHED → rowCount가 설정됨
 * status=FAILED   → error에 오류 메시지가 담김
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AsyncQueryStatus(
        String queryId,
        QueryStatus status,
        String sessionUser,
        long submittedAt,
        Long completedAt,
        Long elapsedMs,
        Integer rowCount,
        String error
) {}
