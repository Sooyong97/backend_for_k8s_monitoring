package com.k8s.agent.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 이상탐지 알림 서비스
 * 이상 탐지 시 HTTP POST로 메트릭 데이터 전송
 */
@Service
public class AnomalyNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyNotificationService.class);

    private final WebClient webClient;
    private final String analyzeEndpoint;

    public AnomalyNotificationService(
            WebClient.Builder webClientBuilder,
            @Value("${ai.agent.base-url:http://10.0.2.134:8000}") String baseUrl) {
        this.analyzeEndpoint = baseUrl + "/analyze";
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
        log.info("AnomalyNotificationService 초기화 완료. Analyze endpoint: {}", this.analyzeEndpoint);
    }

    /**
     * 이상탐지 결과를 analyze 엔드포인트로 전송
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
                            error -> log.error("이상탐지 알림 전송 실패: node={}, metric={}", node, metricName, error)
                    );
            
        } catch (Exception e) {
            log.error("이상탐지 알림 전송 중 예외 발생: node={}, metric={}", node, metricName, e);
        }
    }
}

