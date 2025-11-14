package com.k8s.agent.backend.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ChatSessionService {

    // (임시) 메모리 기반 세션 저장소. <RoomId, ChatSession>
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    // 대화 메시지 DTO
    @Data
    public static class ChatMessage {
        private String role; // "user" or "ai"
        private String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    // 채팅방 세션 정보
    @Data
    public static class ChatSession {
        private final String roomId;
        // AI의 첫 분석 결과 (이 방의 '주제'가 됨)
        private final String initialContext;
        // 이 방의 대화 내역
        private final List<ChatMessage> history = new CopyOnWriteArrayList<>();

        public ChatSession(String roomId, String initialContext) {
            this.roomId = roomId;
            this.initialContext = initialContext;
            // 시스템 메시지로 AI의 첫 분석 결과를 추가
            history.add(new ChatMessage("ai", initialContext));
        }
    }

    /**
     * AI의 첫 분석 결과로 새 채팅 세션을 생성합니다.
     */
    public ChatSession createSession(String roomId, String initialAnalysis) {
        ChatSession session = new ChatSession(roomId, initialAnalysis);
        sessions.put(roomId, session);
        return session;
    }

    /**
     * ID로 세션을 가져옵니다.
     */
    public ChatSession getSession(String roomId) {
        return sessions.get(roomId);
    }

    /**
     * 대화 내역에 메시지를 추가합니다.
     */
    public void addMessageToHistory(String roomId, String role, String content) {
        ChatSession session = sessions.get(roomId);
        if (session != null) {
            session.getHistory().add(new ChatMessage(role, content));
        }
    }
}