package com.company.trino.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/query 및 /api/query/stream, /api/query/async 공통 요청 본문.
 *
 * maxRows:
 *   - 동기 쿼리(/query): null이면 서비스에서 기본값 1000 적용
 *   - 스트림(/query/stream): null 또는 0이면 무제한 (서버 메모리가 아닌 클라이언트가 소비)
 *   - 비동기(/query/async): null이면 서비스에서 기본값 1000 적용
 */
public record QueryRequest(
        @NotBlank(message = "sql is required") String sql,
        @Min(0) @Max(100_000) Integer maxRows
) {}
