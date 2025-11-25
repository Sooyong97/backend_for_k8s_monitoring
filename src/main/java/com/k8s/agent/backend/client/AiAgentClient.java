package com.k8s.agent.backend.client;

import com.k8s.agent.backend.service.ChatSessionService.ChatMessage;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

// Python FastAPI 서버를 호출하기 위한 HTTP 클라이언트
@Component
public class AiAgentClient {

    private static final Logger log = LoggerFactory.getLogger(AiAgentClient.class);
    private final WebClient webClient;
    private final String baseUrl;

    // application.properties에 AI 에이전트의 기본 URL 추가
    // 예: ai.agent.base-url=http://10.0.2.134:8000
    public AiAgentClient(WebClient.Builder builder, @Value("${ai.agent.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.webClient = builder
                .baseUrl(baseUrl)
                .build();
        log.info("AiAgentClient 초기화 완료. Base URL: {}", baseUrl);
    }

    // Python /chat 엔드포인트에 보낼 DTO
    // (Java 필드명을 camelCase로)
    @Data
    private static class ChatRequest {
        // (Java: initialContext) -> JSON: initial_context
        private final String initialContext;

        // (Java: history) -> JSON: history
        private final List<ChatMessage> history;

        // (Java: userMessage) -> JSON: user_message
        private final String userMessage;
    }

    // Python /chat 엔드포인트에서 받을 DTO
    // (Java 필드명을 camelCase로 수정)
    @Data
    private static class ChatResponse {
        // JSON: ai_answer -> (Java: aiAnswer)
        private String aiAnswer;
    }

    /**
     * AI 에이전트의 /chat 엔드포인트를 호출하여 대화형 답변을 받습니다.
     */
    public Mono<String> getChatResponse(String initialContext, List<ChatMessage> history, String userMessage) {

        // camelCase 필드를 가진 ChatRequest 생성
        ChatRequest request = new ChatRequest(initialContext, history, userMessage);

        log.debug("AI Agent 호출 시작. URL: {}/chat, 사용자 메시지 길이: {}", baseUrl, userMessage.length());

        return this.webClient.post()
                .uri("/chat") // AI Agent의 새 엔드포인트
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request) // (Jackson이 ChatRequest를 snake_case JSON으로 직렬화)
                .retrieve()
                .bodyToMono(ChatResponse.class) // (Jackson이 snake_case JSON을 ChatResponse로 역직렬화)
                // camelCase 필드명의 getter 호출
                .map(ChatResponse::getAiAnswer)
                .timeout(Duration.ofSeconds(60)) // 60초 타임아웃
                .doOnSuccess(response -> {
                    log.debug("AI Agent 응답 수신 성공. 응답 길이: {}", response != null ? response.length() : 0);
                })
                .doOnError(error -> {
                    if (error instanceof WebClientResponseException) {
                        WebClientResponseException webClientError = (WebClientResponseException) error;
                        HttpStatusCode status = webClientError.getStatusCode();
                        String responseBody = webClientError.getResponseBodyAsString();
                        log.error("AI Agent HTTP 에러 발생. Status: {}, URL: {}/chat, Response Body: {}", 
                                status, baseUrl, responseBody, webClientError);
                    } else if (error instanceof java.util.concurrent.TimeoutException) {
                        log.error("AI Agent 호출 타임아웃 발생. URL: {}/chat (60초 초과)", baseUrl, error);
                    } else if (error instanceof java.net.ConnectException || 
                               error.getMessage() != null && error.getMessage().contains("Connection refused")) {
                        log.error("AI Agent 서버 연결 실패. URL: {}/chat - 서버가 실행 중인지 확인하세요.", baseUrl, error);
                    } else {
                        log.error("AI Agent 호출 중 예기치 않은 에러 발생. URL: {}/chat", baseUrl, error);
                    }
                });
    }

    /**
     * AI 에이전트의 /chatm 엔드포인트를 호출하여 메뉴얼 전용 RAG 기반 답변을 받습니다.
     */
    public Mono<String> getManualChatResponse(List<ChatMessage> history, String userMessage) {
        // 메뉴얼 채팅은 initialContext를 빈 문자열로 전송 (Python 서버가 null을 허용하지 않음)
        ChatRequest request = new ChatRequest("", history, userMessage);

        log.debug("AI Agent 메뉴얼 채팅 호출 시작. URL: {}/chatm, 사용자 메시지 길이: {}", baseUrl, userMessage.length());

        return this.webClient.post()
                .uri("/chatm") // AI Agent의 메뉴얼 전용 엔드포인트
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatResponse.class)
                .map(ChatResponse::getAiAnswer)
                .timeout(Duration.ofSeconds(60))
                .doOnSuccess(response -> {
                    log.debug("AI Agent 메뉴얼 채팅 응답 수신 성공. 응답 길이: {}", response != null ? response.length() : 0);
                })
                .doOnError(error -> {
                    if (error instanceof WebClientResponseException) {
                        WebClientResponseException webClientError = (WebClientResponseException) error;
                        HttpStatusCode status = webClientError.getStatusCode();
                        String responseBody = webClientError.getResponseBodyAsString();
                        log.error("AI Agent 메뉴얼 채팅 HTTP 에러 발생. Status: {}, URL: {}/chatm, Response Body: {}", 
                                status, baseUrl, responseBody, webClientError);
                    } else if (error instanceof java.util.concurrent.TimeoutException) {
                        log.error("AI Agent 메뉴얼 채팅 호출 타임아웃 발생. URL: {}/chatm (60초 초과)", baseUrl, error);
                    } else if (error instanceof java.net.ConnectException || 
                               error.getMessage() != null && error.getMessage().contains("Connection refused")) {
                        log.error("AI Agent 서버 연결 실패. URL: {}/chatm - 서버가 실행 중인지 확인하세요.", baseUrl, error);
                    } else {
                        log.error("AI Agent 메뉴얼 채팅 호출 중 예기치 않은 에러 발생. URL: {}/chatm", baseUrl, error);
                    }
                });
    }
}