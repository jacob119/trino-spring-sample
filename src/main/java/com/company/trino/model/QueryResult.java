package com.company.trino.model;

import java.util.List;
import java.util.Map;

/**
 * POST /api/query 응답 본문.
 * 서비스 계정 정보(authenticatedAs)는 노출하지 않는다.
 */
public record QueryResult(
        String sessionUser,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        long elapsedMs
) {}
