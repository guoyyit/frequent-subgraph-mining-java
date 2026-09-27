package com.github.guoyyit.frequentsubgraphminingjava.web;

import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningLimitsExceededException;
import com.github.guoyyit.frequentsubgraphminingjava.store.PersistenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将参数错误、规模超限、持久化故障映射为明确的 HTTP 状态与错误信息，
 * 避免异常堆栈直接暴露给调用方。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(MiningLimitsExceededException.class)
    public ResponseEntity<Map<String, Object>> handleLimit(MiningLimitsExceededException e) {
        // 422：输入本身合法但违反配置的规模上限
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "LIMITS_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(PersistenceException.class)
    public ResponseEntity<Map<String, Object>> handlePersistence(PersistenceException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "PERSISTENCE_ERROR", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
