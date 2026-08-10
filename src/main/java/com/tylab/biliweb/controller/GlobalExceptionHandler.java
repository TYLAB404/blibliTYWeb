package com.tylab.biliweb.controller;

import com.tylab.biliweb.api.BiliApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.Map;

/** 全局异常 → JSON 错误信息 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(error(e.getMessage()));
    }

    @ExceptionHandler(BiliApiException.class)
    public ResponseEntity<Map<String, Object>> biliApi(BiliApiException e) {
        log.warn("B站API错误 code={}: {}", e.getCode(), e.getMessage());
        HttpStatus status = e.getCode() == -404 || e.getCode() == -10403
                ? HttpStatus.FORBIDDEN : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(error(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(e.getMessage()));
    }

    private Map<String, Object> error(String message) {
        return Collections.singletonMap("error", message == null ? "未知错误" : message);
    }
}
