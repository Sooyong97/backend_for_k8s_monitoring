package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.dto.CreateAlarmRequest;
import com.k8s.agent.backend.dto.UpdateAlarmRequest;
import com.k8s.agent.backend.entity.Alarm;
import com.k8s.agent.backend.service.AlarmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 알람 API 컨트롤러
 */
@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
@Slf4j
public class AlarmController {

    private final AlarmService alarmService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 알람 목록 조회
     * GET /api/alarms
     */
    @GetMapping
    public ResponseEntity<?> getAlarms(
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        try {
            Alarm.Severity severityEnum = severity != null ? Alarm.Severity.valueOf(severity.toUpperCase()) : null;
            Alarm.AlarmType typeEnum = type != null ? Alarm.AlarmType.valueOf(type.toUpperCase()) : null;

            Page<Alarm> page = alarmService.getAlarms(resolved, severityEnum, typeEnum, limit, offset);

            // Option 1: 배열 직접 반환 (권장)
            return ResponseEntity.ok(page.getContent());

            // Option 2 또는 3을 사용하려면 아래 주석 해제
            /*
            Map<String, Object> response = new HashMap<>();
            response.put("alarms", page.getContent());
            response.put("total", page.getTotalElements());
            response.put("limit", limit);
            response.put("offset", offset);
            return ResponseEntity.ok(response);
            */
        } catch (IllegalArgumentException e) {
            log.error("Invalid filter parameter", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Invalid filter parameter: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Failed to fetch alarms", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to fetch alarms");
            error.put("error", e.getMessage());
            
            // 프론트엔드로 에러 알림 전송
            sendErrorNotification("알람 조회 실패", "Failed to fetch alarms: " + e.getMessage(), "DATABASE_ERROR");
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // 반복 요청 로깅 제한을 위한 간단한 캐시
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> lastLogTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOG_THROTTLE_MS = 5000; // 5초마다 한 번만 로그

    /**
     * 알람 생성 (비활성화)
     * POST /api/alarms
     * 
     * 알람은 AI 서버가 분석 기록을 전송할 때만 자동으로 생성됩니다.
     * 프론트엔드에서 직접 알람을 생성할 수 없습니다.
     */
    @PostMapping
    public ResponseEntity<?> createAlarm(@Valid @RequestBody CreateAlarmRequest request) {
        // 로그 스로틀링: 같은 제목의 요청은 5초마다 한 번만 로그
        String logKey = request.getTitle() != null ? request.getTitle() : "unknown";
        long now = System.currentTimeMillis();
        Long lastLog = lastLogTime.get(logKey);
        
        if (lastLog == null || (now - lastLog) > LOG_THROTTLE_MS) {
            log.debug("프론트엔드에서 알람 생성 시도 (비활성화됨): type={}, severity={}, title={}", 
                    request.getType(), request.getSeverity(), request.getTitle());
            lastLogTime.put(logKey, now);
        }
        
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("message", "알람은 AI 서버가 분석 기록을 전송할 때만 자동으로 생성됩니다. 직접 생성할 수 없습니다.");
        error.put("error", "Alarm creation is disabled. Alarms are only created automatically when AI server sends analysis reports.");
        
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    /**
     * 알람 업데이트
     * PUT /api/alarms/:id
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateAlarm(
            @PathVariable String id,
            @RequestBody UpdateAlarmRequest request
    ) {
        try {
            Alarm alarm = alarmService.updateAlarm(id, request);
            return ResponseEntity.ok(alarm);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "error");
                error.put("message", "Alarm not found");
                error.put("error", e.getMessage());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }
            throw e;
        } catch (Exception e) {
            log.error("Failed to update alarm", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to update alarm");
            error.put("error", e.getMessage());
            
            // 프론트엔드로 에러 알림 전송
            sendErrorNotification("알람 업데이트 실패", "Failed to update alarm: " + e.getMessage(), "DATABASE_ERROR");
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 알람 해결
     * PUT /api/alarms/:id/resolve
     */
    @PutMapping("/{id}/resolve")
    public ResponseEntity<?> resolveAlarm(@PathVariable String id) {
        try {
            Alarm alarm = alarmService.resolveAlarm(id);
            return ResponseEntity.ok(alarm);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "error");
                error.put("message", "Alarm not found");
                error.put("error", e.getMessage());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }
            throw e;
        } catch (Exception e) {
            log.error("Failed to resolve alarm", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to resolve alarm");
            error.put("error", e.getMessage());
            
            // 프론트엔드로 에러 알림 전송
            sendErrorNotification("알람 해결 실패", "Failed to resolve alarm: " + e.getMessage(), "DATABASE_ERROR");
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 알람 삭제
     * DELETE /api/alarms/:id
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAlarm(@PathVariable String id) {
        try {
            alarmService.deleteAlarm(id);
            // 204 No Content 반환 (권장)
            return ResponseEntity.noContent().build();

            // 메시지 포함하려면 아래 주석 해제
            /*
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Alarm deleted successfully");
            return ResponseEntity.ok(response);
            */
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "error");
                error.put("message", "Alarm not found");
                error.put("error", e.getMessage());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }
            throw e;
        } catch (Exception e) {
            log.error("Failed to delete alarm", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to delete alarm");
            error.put("error", e.getMessage());
            
            // 프론트엔드로 에러 알림 전송
            sendErrorNotification("알람 삭제 실패", "Failed to delete alarm: " + e.getMessage(), "DATABASE_ERROR");
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
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

