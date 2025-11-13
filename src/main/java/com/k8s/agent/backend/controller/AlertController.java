package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.dto.AgentAnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    // WebSocket 브로커(/topic)로 메시지를 전송하기 위한 템플릿
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * AI 에이전트가 분석 결과를 POST하는 엔드포인트입니다.
     * (application.properties의 'server.port=8080'에서 동작)
     */
    @PostMapping("/api/v1/alerts/report")
    public ResponseEntity<Void> receiveAiReport(@RequestBody AgentAnalysisResponse result) {

        log.info("AI 리포트 수신 ({}): {}", result.getAnalysisType(), result.getFinalAnswer().substring(0, 50));

        // 1. 수신한 DTO 객체를 "/topic/alerts" 토픽으로 전송
        // 2. 이 토픽을 subscribe 중인 프론트엔드 클라이언트가 이 메시지를 받음
        messagingTemplate.convertAndSend("/topic/alerts", result);

        return ResponseEntity.ok().build();
    }
}