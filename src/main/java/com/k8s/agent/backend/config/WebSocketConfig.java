package com.k8s.agent.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker // WebSocket 메시지 브로커 활성화
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 프론트엔드가 WebSocket 연결을 맺을 엔드포인트
        // (예: ws://localhost:8080/ws)
        registry.addEndpoint("/ws")
                // 모든 도메인에서의 접속을 허용 (CORS)
                .setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // "/topic"으로 시작하는 Destination을 구독하는 클라이언트에게
        // 메시지를 브로드캐스트하는 간단한 메모리 브로커를 활성화
        registry.enableSimpleBroker("/topic");

        // (만약 프론트 -> 백엔드로 "채팅" 메시지를 보낼 경우)
        // "/app"으로 시작하는 목적지는 @MessageMapping 컨트롤러로 라우팅
        registry.setApplicationDestinationPrefixes("/app");
    }
}
