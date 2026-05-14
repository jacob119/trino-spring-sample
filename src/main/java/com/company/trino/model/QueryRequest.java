package com.company.trino.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/query 요청 본문.
 * maxRows가 생략되면 컴팩트 생성자에서 기본값 1000을 적용한다.
 */
public record QueryRequest(
        @NotBlank(message = "sql is required") String sql,
        @Min(1) @Max(100_000) Integer maxRows
) {
    public QueryRequest {
        if (maxRows == null) maxRows = 1000;
    }
}
