package com.k8s.agent.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 전역 예외 핸들러
 * JSON 파싱 에러 등 공통 에러 처리
 * 에러 발생 시 프론트엔드로 실시간 알림 전송
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * JSON 파싱 에러 처리 (400 Bad Request)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e) {
        log.error("JSON 파싱 에러 발생", e);
        
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("message", "Invalid JSON format");
        error.put("error", e.getMessage());
        
        // 프론트엔드로 에러 알림 전송
        sendErrorNotification("JSON 파싱 에러", "Invalid JSON format: " + e.getMessage(), "BAD_REQUEST");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * 일반 예외 처리
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("예기치 않은 에러 발생", e);
        
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("message", "Internal server error");
        error.put("error", e.getMessage());
        
        // 프론트엔드로 에러 알림 전송
        sendErrorNotification("서버 에러", "Internal server error: " + e.getMessage(), "INTERNAL_SERVER_ERROR");
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * 프론트엔드로 에러 알림 전송
     * 주의: 이것은 알람이 아닙니다. 단순 에러 알림입니다.
     * 알람은 AI 서버가 분석 기록을 전송할 때만 생성됩니다.
     */
    private void sendErrorNotification(String title, String message, String type) {
        try {
            Map<String, Object> errorNotification = new HashMap<>();
            errorNotification.put("type", "error_notification"); // "error" 대신 "error_notification" 사용
            errorNotification.put("title", title);
            errorNotification.put("message", message);
            errorNotification.put("errorType", type);
            errorNotification.put("timestamp", LocalDateTime.now().toString());
            errorNotification.put("severity", "error");
            errorNotification.put("shouldCreateAlarm", false); // 알람 생성 금지 플래그
            errorNotification.put("isAlarm", false); // 알람이 아님을 명시
            
            messagingTemplate.convertAndSend("/topic/errors", errorNotification);
            log.debug("에러 알림 전송: {}", title); // INFO -> DEBUG로 변경하여 로그 축소
        } catch (Exception ex) {
            log.warn("에러 알림 전송 실패", ex);
        }
    }
}

