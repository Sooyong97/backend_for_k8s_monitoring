package com.k8s.agent.backend.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
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

    public AnomalyNotificationService(
            WebClient.Builder webClientBuilder,
            @Value("${ai.agent.base-url:http://10.0.2.134:8000}") String baseUrl) {
        this.analyzeEndpoint = baseUrl + "/analyze";
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
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
     * 중복 알림은 10분 단위로 한 번만 전송
     * @param node 노드 식별자
     * @param metricName 메트릭 이름
     * @param value 현재 메트릭 값
     * @param severity 이상탐지 심각도
     * @param message 이상탐지 메시지
     * @param additionalData 추가 데이터 (timestamp, instance 등)
     */
    public void sendAnomalyNotification(
            String node,
            String metricName,
            double value,
            AnomalyDetectionService.Severity severity,
            String message,
            Map<String, Object> additionalData) {
        
        // 애플리케이션 종료 중이면 알림 전송 중단
        if (isShuttingDown.get()) {
            log.debug("애플리케이션 종료 중 - 알림 전송 건너뜀: node={}, metric={}", node, metricName);
            return;
        }
        
        // 중복 알림 방지: 같은 이상은 10분마다 한 번만 전송
        String notificationKey = String.format("%s:%s:%s", node, metricName, severity.name());
        long currentTime = System.currentTimeMillis() / 1000; // Unix timestamp (초)
        
        Long lastSentTime = lastNotificationTime.get(notificationKey);
        if (lastSentTime != null) {
            long timeSinceLastNotification = currentTime - lastSentTime;
            if (timeSinceLastNotification < NOTIFICATION_COOLDOWN_SECONDS) {
                long remainingSeconds = NOTIFICATION_COOLDOWN_SECONDS - timeSinceLastNotification;
                log.debug("중복 알림 방지: node={}, metric={}, severity={}, 남은 시간={}초", 
                        node, metricName, severity, remainingSeconds);
                return;
            }
        }
        
        // 마지막 전송 시간 업데이트
        lastNotificationTime.put(notificationKey, currentTime);
        
        try {
            // 전송할 JSON 데이터 구성
            Map<String, Object> payload = new HashMap<>();
            payload.put("node", node);
            payload.put("metric_name", metricName);
            payload.put("value", value);
            payload.put("severity", severity.name());
            payload.put("message", message);
            payload.put("timestamp", System.currentTimeMillis() / 1000); // Unix timestamp (초)
            
            // 추가 데이터 병합
            if (additionalData != null) {
                payload.putAll(additionalData);
            }
            
            log.info("이상탐지 알림 전송: node={}, metric={}, severity={}, value={}", 
                    node, metricName, severity, value);
            log.debug("전송 데이터: {}", payload);
            
            // HTTP POST 전송 (비동기) - WebClient가 자동으로 JSON으로 변환
            webClient.post()
                    .uri("/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .subscribe(
                            response -> log.debug("이상탐지 알림 전송 성공: {}", response),
                            error -> {
                                // 연결 오류는 간단히 로깅 (너무 긴 스택 트레이스 방지)
                                String errorMsg = error.getMessage();
                                if (errorMsg != null && errorMsg.contains("prematurely closed")) {
                                    log.warn("이상탐지 알림 전송 실패 (연결 종료): node={}, metric={}, error={}", 
                                            node, metricName, errorMsg);
                                } else {
                                    log.warn("이상탐지 알림 전송 실패: node={}, metric={}, error={}", 
                                            node, metricName, errorMsg != null ? errorMsg : error.getClass().getSimpleName());
                                }
                            }
                    );
            
        } catch (Exception e) {
            log.warn("이상탐지 알림 전송 중 예외 발생: node={}, metric={}, error={}", 
                    node, metricName, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}

