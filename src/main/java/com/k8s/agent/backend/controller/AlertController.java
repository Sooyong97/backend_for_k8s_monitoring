package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.client.AiAgentClient;
import com.k8s.agent.backend.dto.*;
import com.k8s.agent.backend.service.ChatSessionService;
import com.k8s.agent.backend.service.ChatSessionService.ChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.UUID;

@Controller // @RestController가 아님 (WebSocket @MessageMapping을 포함하므로)
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private ChatSessionService chatSessionService;
    @Autowired private AiAgentClient aiAgentClient;

    /**
     * [엔드포인트 1: For AI Agent (HTTP POST)]
     * AI 에이전트가 '최초 분석 결과'를 POST하는 엔드포인트.
     * -> '새 채팅방'을 생성하고 프론트에 알린다.
     */
    @PostMapping("/api/v1/alerts/report")
    @ResponseBody // @Controller에서 JSON을 반환하기 위해 필요
    public ResponseEntity<Void> receiveAiReport(@RequestBody AgentAnalysisResponse result) {

        // 1. 고유한 채팅방 ID 생성
        String roomId = UUID.randomUUID().toString();
        log.info("새 K8s 알람 수신. 새 채팅방 생성: {}", roomId);

        // 2. Chat 세션 생성 (AI의 첫 답변을 '초기 컨텍스트'로 저장)
        chatSessionService.createSession(roomId, result.getFinalAnswer());

        // 3. 프론트엔드의 '/topic/notifications' 토픽으로 "새 방 생성" 알림 전송
        NewChatRoomNotification notification = new NewChatRoomNotification(roomId, result);
        messagingTemplate.convertAndSend("/topic/notifications", notification);

        return ResponseEntity.ok().build();
    }

    /**
     * [엔드포인트 2: For Frontend (WebSocket)]
     * 프론트엔드 사용자가 '채팅 메시지'를 전송하는 엔드포인트.
     * (예: /app/chat/abc-123-uuid)
     */
    @MessageMapping("/chat/{roomId}")
    public void handleUserChat(@Payload UserMessage userMessage, @DestinationVariable String roomId) {

        log.info("Room[{}]: 사용자 메시지 수신: {}", roomId, userMessage.getContent());

        // 1. 현재 채팅방의 세션 정보 가져오기
        ChatSession session = chatSessionService.getSession(roomId);
        if (session == null) {
            log.error("Room[{}]: 존재하지 않는 채팅방입니다.", roomId);
            return;
        }

        // 2. 사용자 메시지를 대화 내역에 추가
        chatSessionService.addMessageToHistory(roomId, "user", userMessage.getContent());

        // 3. AI Agent의 /chat 엔드포인트 호출 (비동기)
        aiAgentClient.getChatResponse(
                session.getInitialContext(),
                session.getHistory(),
                userMessage.getContent()
        ).subscribe(aiAnswer -> {
            // 4. AI의 답변을 대화 내역에 추가
            log.info("Room[{}]: AI 답변 수신: {}", roomId, aiAnswer.substring(0, 30));
            chatSessionService.addMessageToHistory(roomId, "ai", aiAnswer);

            // 5. AI의 답변을 해당 방('/topic/room/{roomId}')을 구독 중인 사용자에게 전송
            AiMessage aiMessage = new AiMessage(aiAnswer, "ai");
            messagingTemplate.convertAndSend("/topic/room/" + roomId, aiMessage);

        }, error -> {
            // 에러 처리
            log.error("Room[{}]: AI Agent 호출 실패: {}", roomId, error.getMessage());
            AiMessage errorMessage = new AiMessage("AI 응답 생성 중 오류가 발생했습니다.", "ai");
            messagingTemplate.convertAndSend("/topic/room/" + roomId, errorMessage);
        });
    }
}