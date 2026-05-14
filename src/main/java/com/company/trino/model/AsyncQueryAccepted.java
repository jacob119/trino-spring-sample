package com.company.trino.model;

/**
 * POST /api/query/async 요청 수락(202) 응답.
 *
 * 클라이언트는 statusUrl을 폴링하여 완료 여부를 확인한다.
 * 상태가 FINISHED이면 resultsUrl에서 결과를 가져온다.
 */
public record AsyncQueryAccepted(
        String queryId,
        String statusUrl,
        String resultsUrl
) {}
