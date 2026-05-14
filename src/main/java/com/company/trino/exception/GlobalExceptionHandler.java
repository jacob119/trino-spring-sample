package com.company.trino.exception;

import com.company.trino.service.TrinoQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전역 예외 핸들러.
 *
 * 내부 상세 정보(SQL 오류 메시지, 스택 트레이스 등)는 서버 로그에만 기록하고,
 * 클라이언트에는 안전한 일반 메시지만 반환한다.
 *
 * HTTP 상태 코드 매핑:
 *   400 Bad Request      — 잘못된 요청 (헤더 누락, 유효성 실패)
 *   403 Forbidden        — impersonation 거부 (ImpersonationDeniedException)
 *   503 Service Unavail. — Trino 연결/쿼리 실패 (TrinoQueryException)
 *   500 Internal Error   — 예상치 못한 서버 오류
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "required header missing: " + e.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(Map.of("error", errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ImpersonationDeniedException.class)
    public ResponseEntity<Map<String, String>> handleImpersonationDenied(ImpersonationDeniedException e) {
        log.warn("[Security] impersonation denied for: {}", e.getSessionUser());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "impersonation denied"));
    }

    @ExceptionHandler(TrinoQueryService.TrinoQueryException.class)
    public ResponseEntity<Map<String, String>> handleTrinoError(TrinoQueryService.TrinoQueryException e) {
        // 상세 오류는 서버 로그에만 기록 — 클라이언트에 내부 메시지 노출 금지
        log.error("[Trino] query execution failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "query execution failed"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception e) {
        log.error("[Server] unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "internal server error"));
    }
}
