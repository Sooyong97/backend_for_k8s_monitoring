package com.k8s.agent.backend.client;

import com.k8s.agent.backend.service.ChatSessionService.ChatMessage;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

// Python FastAPI 서버를 호출하기 위한 HTTP 클라이언트
@Component
public class AiAgentClient {

    private final WebClient webClient;

    // application.properties에 AI 에이전트의 기본 URL 추가
    // 예: ai.agent.base-url=http://10.0.2.134:8000
    public AiAgentClient(WebClient.Builder builder, @Value("${ai.agent.base-url}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
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

        return this.webClient.post()
                .uri("/chat") // AI Agent의 새 엔드포인트
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request) // (Jackson이 ChatRequest를 snake_case JSON으로 직렬화)
                .retrieve()
                .bodyToMono(ChatResponse.class) // (Jackson이 snake_case JSON을 ChatResponse로 역직렬화)
                // camelCase 필드명의 getter 호출
                .map(ChatResponse::getAiAnswer);
    }
}