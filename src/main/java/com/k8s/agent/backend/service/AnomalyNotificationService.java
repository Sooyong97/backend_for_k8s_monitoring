package com.k8s.agent.backend.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 이상탐지 알림 서비스
 * 이상 탐지 시 HTTP POST로 메트릭 데이터 전송
 * 중복 알림은 10분 단위로 한 번만 전송
 */
@Service
public class AnomalyNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyNotificationService.class);

    // 중복 알림 방지: 10분(600초) 간격
    private static final long NOTIFICATION_COOLDOWN_SECONDS = 600;

    private final WebClient webClient;
    private final String analyzeEndpoint;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    
    // 마지막 알림 전송 시간 추적: key = "node:metricName:severity"
    private final Map<String, Long> lastNotificationTime = new ConcurrentHashMap<>();
    
    // 마지막 severity 추적: key = "node:metricName", value = severity name
    private final Map<String, String> lastSeverityMap = new ConcurrentHashMap<>();

    public AnomalyNotificationService(
            WebClient.Builder webClientBuilder,
            @Value("${ai.agent.base-url:http://10.0.2.134:8000}") String baseUrl) {
        this.analyzeEndpoint = baseUrl + "/analyze";
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();
        log.info("AnomalyNotificationService 초기화 완료. Analyze endpoint: {}", this.analyzeEndpoint);
    }

    @PreDestroy
    public void shutdown() {
        isShuttingDown.set(true);
        log.info("AnomalyNotificationService 종료 중 - 새로운 알림 전송 중단");
    }

    /**
     * 이상탐지 결과를 analyze 엔드포인트로 전송
     * Prometheus에서 가져온 원본 메트릭 데이터를 그대로 전송
     * 중복 알림은 10분 단위로 한 번만 전송
     * @param node 노드 식별자
     * @param metricName 메트릭 이름
     * @param severity 이상탐지 심각도
     * @param message 이상탐지 메시지
     * @param originalMetric Prometheus 원본 메트릭 데이터 (metric + value)
     */
    public void sendAnomalyNotification(
            String node,
            String metricName,
            AnomalyDetectionService.Severity severity,
            String message,
            Map<String, Object> originalMetric) {
        
        sendNotification(node, metricName, severity, message, originalMetric, "metric");
    }

    /**
     * ERROR 로그를 analyze 엔드포인트로 전송
     * Loki에서 가져온 원본 로그 데이터를 그대로 전송
     * 중복 알림은 10분 단위로 한 번만 전송
     * @param logData Loki 원본 로그 데이터 (stream + values)
     */
    public void sendErrorLogNotification(Map<String, Object> logData) {
        log.debug("sendErrorLogNotification 호출됨: logData={}", logData);
        
        // stream에서 노드 정보 추출
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) logData.get("stream");
        String node = "unknown";
        if (stream != null) {
            if (stream.containsKey("node")) {
                node = stream.get("node");
            } else if (stream.containsKey("instance")) {
                String instance = stream.get("instance");
                node = instance.contains(":") ? instance.split(":")[0] : instance;
            } else if (stream.containsKey("pod")) {
                node = stream.get("pod");
            }
        }
        
        // 로그 메시지 추출
        String logMessage = "ERROR 로그 감지";
        @SuppressWarnings("unchecked")
        List<List<String>> values = (List<List<String>>) logData.get("values");
        if (values != null && !values.isEmpty() && values.get(0).size() >= 2) {
            logMessage = values.get(0).get(1); // 첫 번째 로그 라인
        }
        
        log.info("ERROR 로그 알림 전송 시작: node={}, message={}", node, 
                logMessage.length() > 100 ? logMessage.substring(0, 100) + "..." : logMessage);
        
        sendNotification(node, "error_log", AnomalyDetectionService.Severity.CRITICAL, 
                        logMessage, logData, "log");
    }

    /**
     * 공통 알림 전송 로직
     */
    private void sendNotification(
            String node,
            String itemName,
            AnomalyDetectionService.Severity severity,
            String message,
            Map<String, Object> originalData,
            String dataType) {
        
        // 애플리케이션 종료 중이면 알림 전송 중단
        if (isShuttingDown.get()) {
            log.debug("애플리케이션 종료 중 - 알림 전송 건너뜀: node={}, item={}, type={}", node, itemName, dataType);
            return;
        }
        
        // 중복 알림 방지: 같은 이상은 10분마다 한 번만 전송
        // 로그의 경우 메시지 해시를 포함하여 더 정확한 중복 방지
        String notificationKey;
        String baseKey; // severity를 제외한 기본 키 (severity 변경 감지용)
        
        if ("log".equals(dataType)) {
            // 로그는 메시지 내용 기반으로 중복 방지 (같은 로그 메시지는 10분마다 한 번만)
            String messageHash = String.valueOf(message.hashCode());
            notificationKey = String.format("%s:%s:%s:%s", node, itemName, severity.name(), messageHash);
            baseKey = String.format("%s:%s:%s", node, itemName, messageHash);
        } else {
            notificationKey = String.format("%s:%s:%s", node, itemName, severity.name());
            baseKey = String.format("%s:%s", node, itemName);
        }
        
        long currentTime = System.currentTimeMillis() / 1000; // Unix timestamp (초)
        
        // 같은 메트릭의 이전 severity 확인
        String lastSeverity = lastSeverityMap.get(baseKey);
        
        // severity가 변경된 경우에만 즉시 전송 (쿨다운 무시)
        boolean severityChanged = lastSeverity != null && !lastSeverity.equals(severity.name());
        
        Long lastSentTime = lastNotificationTime.get(notificationKey);
        if (lastSentTime != null) {
            long timeSinceLastNotification = currentTime - lastSentTime;
            
            // severity가 변경되지 않았고, 10분이 지나지 않았으면 전송하지 않음
            if (!severityChanged && timeSinceLastNotification < NOTIFICATION_COOLDOWN_SECONDS) {
                long remainingSeconds = NOTIFICATION_COOLDOWN_SECONDS - timeSinceLastNotification;
                log.debug("중복 알림 방지: node={}, item={}, type={}, severity={}, 남은 시간={}초", 
                        node, itemName, dataType, severity, remainingSeconds);
                return;
            }
        }
        
        // 마지막 전송 시간 업데이트
        lastNotificationTime.put(notificationKey, currentTime);
        // 마지막 severity 저장
        lastSeverityMap.put(baseKey, severity.name());
        
        try {
            // 원본 데이터를 그대로 사용
            Map<String, Object> payload = new HashMap<>(originalData);
            
            // 이상탐지 정보 추가
            if ("metric".equals(dataType)) {
                payload.put("metric_name", itemName);
            } else {
                payload.put("log_type", "error");
            }
            payload.put("data_type", dataType);
            payload.put("anomaly_detected", true);
            payload.put("severity", severity.name());
            payload.put("message", message);
            payload.put("detection_timestamp", currentTime);
            
            if (severityChanged) {
                log.info("이상탐지 알림 전송 (severity 변경): node={}, item={}, type={}, severity={} (이전: {})", 
                        node, itemName, dataType, severity, lastSeverity);
            } else {
                log.info("이상탐지 알림 전송: node={}, item={}, type={}, severity={}", 
                        node, itemName, dataType, severity);
            }
            log.debug("전송 데이터: {}", payload);
            
            // HTTP POST 전송 (비동기) - WebClient가 자동으로 JSON으로 변환
            webClient.post()
                    .uri("/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30)) // 타임아웃 30초로 증가
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)) // 최대 2번 재시도, 1초 간격
                            .filter(throwable -> {
                                // 타임아웃이나 연결 오류만 재시도
                                String errorMsg = throwable.getMessage();
                                return errorMsg != null && (
                                    errorMsg.contains("timeout") || 
                                    errorMsg.contains("Timeout") ||
                                    errorMsg.contains("prematurely closed") ||
                                    throwable instanceof java.util.concurrent.TimeoutException
                                );
                            })
                            .doBeforeRetry(retrySignal -> 
                                log.debug("이상탐지 알림 재시도: node={}, item={}, type={}, attempt={}", 
                                        node, itemName, dataType, retrySignal.totalRetries() + 1)
                            )
                    )
                    .subscribe(
                            response -> log.debug("이상탐지 알림 전송 성공: {}", response),
                            error -> {
                                // 연결 오류는 간단히 로깅 (너무 긴 스택 트레이스 방지)
                                String errorMsg = error.getMessage();
                                if (errorMsg != null && errorMsg.contains("prematurely closed")) {
                                    log.warn("이상탐지 알림 전송 실패 (연결 종료): node={}, item={}, type={}, error={}", 
                                            node, itemName, dataType, errorMsg);
                                } else if (errorMsg != null && errorMsg.contains("timeout")) {
                                    log.warn("이상탐지 알림 전송 실패 (타임아웃): node={}, item={}, type={}, error={}", 
                                            node, itemName, dataType, errorMsg);
                                } else {
                                    log.warn("이상탐지 알림 전송 실패: node={}, item={}, type={}, error={}", 
                                            node, itemName, dataType, errorMsg != null ? errorMsg : error.getClass().getSimpleName());
                                }
                            }
                    );
            
        } catch (Exception e) {
            log.warn("이상탐지 알림 전송 중 예외 발생: node={}, item={}, type={}, error={}", 
                    node, itemName, dataType, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}

