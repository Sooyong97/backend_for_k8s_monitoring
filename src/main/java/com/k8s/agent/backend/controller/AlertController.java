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
        log.info("Room[{}]: AI Agent 호출 시작. InitialContext 길이: {}, History 크기: {}, UserMessage: {}", 
                roomId, 
                session.getInitialContext() != null ? session.getInitialContext().length() : 0,
                session.getHistory() != null ? session.getHistory().size() : 0,
                userMessage.getContent());
        
        aiAgentClient.getChatResponse(
                session.getInitialContext(),
                session.getHistory(),
                userMessage.getContent()
        ).subscribe(aiAnswer -> {
            // 4. AI의 답변을 대화 내역에 추가
            if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
                log.warn("Room[{}]: AI 답변이 비어있습니다.", roomId);
                aiAnswer = "AI 응답이 비어있습니다. 다시 시도해주세요.";
            } else {
                log.info("Room[{}]: AI 답변 수신 성공. 길이: {}, 미리보기: {}", 
                        roomId, 
                        aiAnswer.length(),
                        aiAnswer.length() > 50 ? aiAnswer.substring(0, 50) + "..." : aiAnswer);
            }
            
            chatSessionService.addMessageToHistory(roomId, "ai", aiAnswer);

            // 5. AI의 답변을 해당 방('/topic/room/{roomId}')을 구독 중인 사용자에게 전송
            AiMessage aiMessage = new AiMessage(aiAnswer, "ai");
            messagingTemplate.convertAndSend("/topic/room/" + roomId, aiMessage);

        }, error -> {
            // 에러 처리 - 에러 타입별로 상세한 로깅 및 메시지 전송
            String errorMessage;
            String logMessage;
            
            // 근본 원인(cause) 확인
            Throwable rootCause = error;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }
            
            if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                // HTTP 응답이 있지만 에러 상태 코드인 경우
                org.springframework.web.reactive.function.client.WebClientResponseException webClientError = 
                        (org.springframework.web.reactive.function.client.WebClientResponseException) error;
                org.springframework.http.HttpStatusCode status = webClientError.getStatusCode();
                String responseBody = webClientError.getResponseBodyAsString();
                int statusValue = status.value();
                
                if (statusValue >= 400 && statusValue < 500) {
                    logMessage = String.format("Room[%s]: AI Agent 클라이언트 에러 (4xx). Status: %s, Response: %s", 
                            roomId, status, responseBody);
                    errorMessage = String.format("AI 서버에서 요청 오류가 발생했습니다. (상태 코드: %s)", statusValue);
                } else if (statusValue >= 500) {
                    logMessage = String.format("Room[%s]: AI Agent 서버 에러 (5xx). Status: %s, Response: %s", 
                            roomId, status, responseBody);
                    errorMessage = "AI 서버에서 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
                } else {
                    logMessage = String.format("Room[%s]: AI Agent HTTP 에러. Status: %s, Response: %s", 
                            roomId, status, responseBody);
                    errorMessage = String.format("AI 서버 통신 중 오류가 발생했습니다. (상태 코드: %s)", statusValue);
                }
                
                log.error(logMessage, webClientError);
                
            } else if (error instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
                // 네트워크 레벨 에러 (연결 실패, 라우팅 실패 등)
                org.springframework.web.reactive.function.client.WebClientRequestException requestException = 
                        (org.springframework.web.reactive.function.client.WebClientRequestException) error;
                
                if (rootCause instanceof java.net.NoRouteToHostException) {
                    logMessage = String.format("Room[%s]: AI Agent 서버로 라우팅 실패 (NoRouteToHostException) - 네트워크 경로를 확인하세요", roomId);
                    errorMessage = "AI 서버로의 네트워크 경로를 찾을 수 없습니다. 네트워크 설정과 방화벽을 확인해주세요.";
                } else if (rootCause instanceof java.net.ConnectException) {
                    logMessage = String.format("Room[%s]: AI Agent 서버 연결 실패 (ConnectException) - 서버가 실행 중인지 확인하세요", roomId);
                    errorMessage = "AI 서버에 연결할 수 없습니다. 서버가 실행 중인지 확인해주세요.";
                } else if (rootCause instanceof java.net.SocketTimeoutException || 
                           rootCause instanceof java.util.concurrent.TimeoutException) {
                    logMessage = String.format("Room[%s]: AI Agent 서버 연결 타임아웃", roomId);
                    errorMessage = "AI 서버 연결 시간이 초과되었습니다. 네트워크 상태를 확인해주세요.";
                } else {
                    logMessage = String.format("Room[%s]: AI Agent 네트워크 요청 실패. Root cause: %s", 
                            roomId, rootCause.getClass().getSimpleName());
                    errorMessage = "AI 서버와의 네트워크 통신에 실패했습니다. 서버 상태와 네트워크 연결을 확인해주세요.";
                }
                
                log.error(logMessage + ". Request URL: " + requestException.getMessage(), requestException);
                
            } else if (error instanceof java.util.concurrent.TimeoutException) {
                logMessage = String.format("Room[%s]: AI Agent 호출 타임아웃 (60초 초과)", roomId);
                errorMessage = "AI 응답 시간이 초과되었습니다. AI 서버가 과부하 상태일 수 있습니다. 잠시 후 다시 시도해주세요.";
                log.error(logMessage, error);
                
            } else if (rootCause instanceof java.net.NoRouteToHostException) {
                logMessage = String.format("Room[%s]: AI Agent 서버로 라우팅 실패 (NoRouteToHostException) - 네트워크 경로를 확인하세요", roomId);
                errorMessage = "AI 서버로의 네트워크 경로를 찾을 수 없습니다. 네트워크 설정과 방화벽을 확인해주세요.";
                log.error(logMessage, error);
                
            } else if (rootCause instanceof java.net.ConnectException) {
                logMessage = String.format("Room[%s]: AI Agent 서버 연결 실패 (ConnectException) - 서버가 실행 중인지 확인하세요", roomId);
                errorMessage = "AI 서버에 연결할 수 없습니다. 서버가 실행 중인지 확인해주세요.";
                log.error(logMessage, error);
                
            } else if (error.getMessage() != null && (
                       error.getMessage().contains("Connection refused") ||
                       error.getMessage().contains("Connection timed out") ||
                       error.getMessage().contains("No route to host"))) {
                logMessage = String.format("Room[%s]: AI Agent 서버 연결 실패 - 서버가 실행 중인지 확인하세요", roomId);
                errorMessage = "AI 서버에 연결할 수 없습니다. 서버 상태를 확인해주세요.";
                log.error(logMessage, error);
                
            } else {
                logMessage = String.format("Room[%s]: AI Agent 호출 중 예기치 않은 에러 발생. Error type: %s, Root cause: %s", 
                        roomId, error.getClass().getName(), rootCause.getClass().getName());
                errorMessage = "AI 응답 생성 중 예기치 않은 오류가 발생했습니다.";
                log.error(logMessage + ". Message: " + error.getMessage(), error);
            }
            
            // 에러 메시지를 프론트엔드에 전송
            AiMessage errorMsg = new AiMessage(errorMessage, "ai");
            messagingTemplate.convertAndSend("/topic/room/" + roomId, errorMsg);
        });
    }
}