package com.example.web;

import com.example.service.VmsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(VmsService.ApiException.class)
    public ResponseEntity<Map<String, String>> handle(VmsService.ApiException ex) {
        return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
    }
}
