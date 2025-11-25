package com.k8s.agent.backend.service;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ChatSessionService {

    // Redis Key: "chat_sessions"라는 Hash(해시맵) 안에 <roomId, ChatSession> 형태로 저장
    private static final String CHAT_SESSIONS_KEY = "chat_sessions";
    private static final long SESSION_TIMEOUT_HOURS = 24; // 세션 만료 시간 (예: 24시간)

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Data
    @NoArgsConstructor // Jackson이 Redis에서 JSON을 읽어 객체로 만들 때
    public static class ChatMessage {
        private String role; // "user" or "ai"
        private String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    @Data
    @NoArgsConstructor // Jackson 역직렬화 시
    public static class ChatSession {
        private String roomId;
        private String initialContext;
        private List<ChatMessage> history = new ArrayList<>();
        private boolean isManualChat = false; // 메뉴얼 채팅 여부

        public ChatSession(String roomId, String initialContext) {
            this.roomId = roomId;
            this.initialContext = initialContext;
        }

        public ChatSession(String roomId, String initialContext, boolean isManualChat) {
            this.roomId = roomId;
            this.initialContext = initialContext;
            this.isManualChat = isManualChat;
        }
    }

    /**
     * AI의 첫 분석 결과로 새 채팅 세션을 생성
     */
    public ChatSession createSession(String roomId, String initialAnalysis) {
        ChatSession session = new ChatSession(roomId, initialAnalysis);

        session.getHistory().add(new ChatMessage("ai", initialAnalysis));

        // HSET "chat_sessions" "roomId" (session 객체 JSON)
        redisTemplate.opsForHash().put(CHAT_SESSIONS_KEY, roomId, session);

        // 24시간 뒤 세션 자동 삭제
        redisTemplate.expire(CHAT_SESSIONS_KEY, SESSION_TIMEOUT_HOURS, TimeUnit.HOURS);

        return session;
    }

    /**
     * 메뉴얼 채팅 세션을 생성
     */
    public ChatSession createManualSession(String roomId) {
        ChatSession session = new ChatSession(roomId, "메뉴얼 채팅입니다.", true);
        
        session.getHistory().add(new ChatMessage("ai", "메뉴얼 채팅입니다. 무엇을 도와드릴까요?"));

        // HSET "chat_sessions" "roomId" (session 객체 JSON)
        redisTemplate.opsForHash().put(CHAT_SESSIONS_KEY, roomId, session);

        // 24시간 뒤 세션 자동 삭제
        redisTemplate.expire(CHAT_SESSIONS_KEY, SESSION_TIMEOUT_HOURS, TimeUnit.HOURS);

        return session;
    }

    /**
     * ID로 세션을 가져옵니다. (Redis에서)
     */
    public ChatSession getSession(String roomId) {
        // HGET "chat_sessions" "roomId"
        return (ChatSession) redisTemplate.opsForHash().get(CHAT_SESSIONS_KEY, roomId);
    }

    /**
     * 대화 내역에 메시지를 추가합니다. (Get, Modify, Set 패턴)
     */
    public void addMessageToHistory(String roomId, String role, String content) {
        // Get: Redis에서 현재 세션을 가져옴
        ChatSession session = getSession(roomId);

        if (session != null) {
            // Modify: Java 메모리에서 history 리스트 수정
            session.getHistory().add(new ChatMessage(role, content));

            // Set: 수정된 세션 객체 전체를 Redis에 덮어씀
            redisTemplate.opsForHash().put(CHAT_SESSIONS_KEY, roomId, session);

            // 세션 만료 시간 갱신
            redisTemplate.expire(CHAT_SESSIONS_KEY, SESSION_TIMEOUT_HOURS, TimeUnit.HOURS);
        }
    }

    /**
     * 모든 채팅 세션 목록 조회 (디버깅/통계용)
     */
    public List<ChatSession> getAllSessions() {
        List<ChatSession> sessions = new ArrayList<>();
        redisTemplate.opsForHash().values(CHAT_SESSIONS_KEY).forEach(value -> {
            if (value instanceof ChatSession) {
                sessions.add((ChatSession) value);
            }
        });
        return sessions;
    }

    /**
     * 채팅 세션 통계 조회
     */
    public Map<String, Object> getSessionStatistics() {
        List<ChatSession> allSessions = getAllSessions();
        long totalSessions = allSessions.size();
        long manualChatSessions = allSessions.stream()
                .filter(session -> session.isManualChat())
                .count();
        long alarmChatSessions = totalSessions - manualChatSessions;

        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalSessions", totalSessions);
        stats.put("manualChatSessions", manualChatSessions);
        stats.put("alarmChatSessions", alarmChatSessions);
        return stats;
    }
}