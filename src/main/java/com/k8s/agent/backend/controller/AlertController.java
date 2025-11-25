package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.client.AiAgentClient;
import com.k8s.agent.backend.dto.*;
import com.k8s.agent.backend.entity.Alarm;
import com.k8s.agent.backend.service.AlarmService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Mono;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller // @RestController가 아님 (WebSocket @MessageMapping을 포함하므로)
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private ChatSessionService chatSessionService;
    @Autowired private AiAgentClient aiAgentClient;
    @Autowired private AlarmService alarmService;
    @Autowired private ObjectMapper objectMapper;

    /**
     * [엔드포인트 1: For AI Agent (HTTP POST)]
     * AI 에이전트가 '최초 분석 결과'를 POST하는 엔드포인트.
     * -> '새 채팅방'을 생성하고 프론트에 알린다.
     */
    @PostMapping("/api/v1/alerts/report")
    @ResponseBody // @Controller에서 JSON을 반환하기 위해 필요
    public ResponseEntity<?> receiveAiReport(@RequestBody AgentAnalysisResponse result) {
        try {
            // 요청 데이터 검증 및 로깅
            log.info("AI Agent 리포트 수신. Status: {}, AnalysisType: {}, ServiceType: {}, FinalAnswer: {}", 
                    result.getStatus(),
                    result.getAnalysisType(),
                    result.getServiceType(),
                    result.getFinalAnswer() != null ? 
                        (result.getFinalAnswer().length() > 100 ? 
                            result.getFinalAnswer().substring(0, 100) + "..." : 
                            result.getFinalAnswer()) : 
                        "null");

            // finalAnswer가 null이거나 비어있으면 에러
            if (result.getFinalAnswer() == null || result.getFinalAnswer().trim().isEmpty()) {
                log.error("AI Agent 리포트 수신 실패: finalAnswer가 비어있습니다. Status: {}, ErrorMessage: {}", 
                        result.getStatus(), result.getErrorMessage());
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "finalAnswer is required"));
            }

            // 1. 고유한 채팅방 ID 생성
            String roomId = UUID.randomUUID().toString();
            log.info("새 K8s 알람 수신. 새 채팅방 생성: {}", roomId);

            // 2. Chat 세션 생성 (AI의 첫 답변을 '초기 컨텍스트'로 저장)
            chatSessionService.createSession(roomId, result.getFinalAnswer());

            // 3. 알람 생성 (AI 리포트를 알람으로 저장)
            try {
                // original_data 추출
                Map<String, Object> originalData = result.getOriginalData();
                
                // 노드 정보 추출
                String nodeName = extractNodeName(originalData);
                
                // 기본 제목 생성
                String titleBase = extractTitleBase(result, originalData);
                
                // 노드 정보가 있으면 제목에 추가
                String title = nodeName != null 
                    ? String.format("[%s] %s", nodeName, titleBase) 
                    : titleBase;
                
                // 태그 생성
                List<String> tags = extractTags(originalData, nodeName);
                
                // severity 추출
                Alarm.Severity severity = extractSeverity(originalData);
                
                // type 추출
                Alarm.AlarmType alarmType = extractAlarmType(originalData);
                
                CreateAlarmRequest alarmRequest = new CreateAlarmRequest();
                alarmRequest.setType(alarmType);
                alarmRequest.setSeverity(severity);
                alarmRequest.setTitle(title); // 파싱된 제목 사용
                alarmRequest.setMessage(result.getFinalAnswer());
                alarmRequest.setSource("AI Agent");
                
                // metadata에 AI 리포트 전체 정보 저장
                Map<String, Object> metadata = new java.util.HashMap<>();
                metadata.put("finalAnswer", result.getFinalAnswer());
                metadata.put("analysisType", result.getAnalysisType());
                metadata.put("serviceType", result.getServiceType());
                metadata.put("status", result.getStatus());
                if (result.getErrorMessage() != null) {
                    metadata.put("errorMessage", result.getErrorMessage());
                }
                metadata.put("roomId", roomId);
                if (originalData != null) {
                    metadata.put("originalData", originalData);
                }
                alarmRequest.setMetadata(metadata);
                
                // node와 tags 설정
                alarmRequest.setNode(nodeName);
                alarmRequest.setTags(tags.isEmpty() ? null : objectMapper.writeValueAsString(tags));
                
                log.info("알람 생성 시도: RoomId={}, Title={}, Node={}, Tags={}", 
                        roomId, title, nodeName, tags);
                Alarm createdAlarm = alarmService.createAlarm(alarmRequest);
                
                log.info("AI 리포트를 알람으로 저장 완료. RoomId={}, AlarmId={}, Title={}", 
                        roomId, createdAlarm.getId(), title);
            } catch (Exception e) {
                log.error("알람 생성 실패 (채팅방은 생성됨). RoomId: {}", roomId, e);
                // 알람 생성 실패해도 채팅방은 생성되므로 계속 진행
            }

            // 4. 프론트엔드의 '/topic/notifications' 토픽으로 "새 방 생성" 알림 전송
            NewChatRoomNotification notification = new NewChatRoomNotification(roomId, result);
            messagingTemplate.convertAndSend("/topic/notifications", notification);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("AI Agent 리포트 처리 실패", e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Failed to process report: " + e.getMessage()));
        }
    }

    /**
     * 노드 정보 추출 (프론트엔드와 동일한 로직)
     */
    private String extractNodeName(Map<String, Object> originalData) {
        if (originalData == null) {
            return null;
        }

        // 1순위: original_data.node
        if (originalData.containsKey("node")) {
            Object node = originalData.get("node");
            return node != null ? String.valueOf(node) : null;
        }

        // 2순위: original_data.metric.node
        if (originalData.containsKey("metric")) {
            Object metricObj = originalData.get("metric");
            if (metricObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metric = (Map<String, Object>) metricObj;
                if (metric.containsKey("node")) {
                    Object node = metric.get("node");
                    return node != null ? String.valueOf(node) : null;
                }
            }
        }

        // 3순위: original_data.instance에서 IP 추출
        if (originalData.containsKey("instance")) {
            Object instanceObj = originalData.get("instance");
            if (instanceObj != null) {
                String instance = String.valueOf(instanceObj);
                if (instance.contains(":")) {
                    return instance.split(":")[0];
                }
                return instance;
            }
        }

        return null;
    }

    /**
     * 기본 제목 추출
     */
    private String extractTitleBase(AgentAnalysisResponse result, Map<String, Object> originalData) {
        // 1순위: original_data.message
        if (originalData != null && originalData.containsKey("message")) {
            Object messageObj = originalData.get("message");
            if (messageObj != null) {
                String message = String.valueOf(messageObj);
                if (!message.trim().isEmpty()) {
                    return message;
                }
            }
        }

        // 2순위: finalAnswer의 첫 부분 사용
        if (result.getFinalAnswer() != null && !result.getFinalAnswer().trim().isEmpty()) {
            String finalAnswer = result.getFinalAnswer();
            // 첫 문장만 사용 (줄바꿈이나 마침표 기준)
            String firstSentence = finalAnswer.split("[\\n\\.]")[0].trim();
            if (!firstSentence.isEmpty() && firstSentence.length() <= 100) {
                return firstSentence;
            }
            // 너무 길면 앞부분만 사용
            return finalAnswer.length() > 100 ? finalAnswer.substring(0, 100) + "..." : finalAnswer;
        }

        // 3순위: severity 기반 기본 제목
        String severity = extractSeverityString(originalData);
        return String.format("K8s Alert (%s)", severity);
    }

    /**
     * 태그 생성
     */
    private List<String> extractTags(Map<String, Object> originalData, String nodeName) {
        List<String> tags = new ArrayList<>();

        if (nodeName != null) {
            tags.add(nodeName);
        }

        if (originalData != null) {
            // metric_name 추가
            if (originalData.containsKey("metric_name")) {
                Object metricNameObj = originalData.get("metric_name");
                if (metricNameObj != null) {
                    tags.add(String.valueOf(metricNameObj));
                }
            }

            // namespace 추가
            if (originalData.containsKey("namespace")) {
                Object namespaceObj = originalData.get("namespace");
                if (namespaceObj != null) {
                    tags.add(String.valueOf(namespaceObj));
                }
            }

            // metric.namespace도 확인
            if (originalData.containsKey("metric")) {
                Object metricObj = originalData.get("metric");
                if (metricObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metric = (Map<String, Object>) metricObj;
                    if (metric.containsKey("namespace")) {
                        Object namespaceObj = metric.get("namespace");
                        if (namespaceObj != null && !tags.contains(String.valueOf(namespaceObj))) {
                            tags.add(String.valueOf(namespaceObj));
                        }
                    }
                }
            }
        }

        return tags;
    }

    /**
     * Severity 추출
     */
    private Alarm.Severity extractSeverity(Map<String, Object> originalData) {
        if (originalData != null && originalData.containsKey("severity")) {
            try {
                String severityStr = String.valueOf(originalData.get("severity"));
                return Alarm.Severity.valueOf(severityStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.debug("Invalid severity value: {}", originalData.get("severity"));
            }
        }
        return Alarm.Severity.CRITICAL; // 기본값
    }

    /**
     * Severity 문자열 추출
     */
    private String extractSeverityString(Map<String, Object> originalData) {
        if (originalData != null && originalData.containsKey("severity")) {
            return String.valueOf(originalData.get("severity")).toUpperCase();
        }
        return "CRITICAL";
    }

    /**
     * AlarmType 추출
     */
    private Alarm.AlarmType extractAlarmType(Map<String, Object> originalData) {
        if (originalData != null && originalData.containsKey("data_type")) {
            String dataType = String.valueOf(originalData.get("data_type"));
            if ("log".equalsIgnoreCase(dataType)) {
                return Alarm.AlarmType.LOG;
            } else if ("metric".equalsIgnoreCase(dataType)) {
                return Alarm.AlarmType.METRIC;
            }
        }
        return Alarm.AlarmType.LOG; // 기본값
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

        // 3. AI Agent 호출 (메뉴얼 채팅인지 확인)
        boolean isManualChat = session.isManualChat();
        log.info("Room[{}]: AI Agent 호출 시작. ManualChat: {}, InitialContext 길이: {}, History 크기: {}, UserMessage: {}", 
                roomId,
                isManualChat,
                session.getInitialContext() != null ? session.getInitialContext().length() : 0,
                session.getHistory() != null ? session.getHistory().size() : 0,
                userMessage.getContent());
        
        // 메뉴얼 채팅이면 /chatm, 일반 채팅이면 /chat 호출
        Mono<String> aiResponseMono = isManualChat 
            ? aiAgentClient.getManualChatResponse(session.getHistory(), userMessage.getContent())
            : aiAgentClient.getChatResponse(session.getInitialContext(), session.getHistory(), userMessage.getContent());
        
        aiResponseMono.subscribe(aiAnswer -> {
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

    /**
     * [엔드포인트 3: For Frontend (HTTP POST)]
     * 메뉴얼 전용 RAG 채팅방 생성 엔드포인트
     * 프론트엔드에서 새 메뉴얼 채팅을 생성할 때 호출
     */
    @PostMapping("/api/chatm")
    @ResponseBody
    public ResponseEntity<?> createManualChatRoom() {
        try {
            // 1. 고유한 채팅방 ID 생성
            String roomId = UUID.randomUUID().toString();
            log.info("새 메뉴얼 채팅방 생성: {}", roomId);

            // 2. 메뉴얼 채팅 세션 생성
            chatSessionService.createManualSession(roomId);

            // 3. roomId 반환
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "roomId", roomId,
                "room_id", roomId  // 프론트엔드 호환성을 위해 둘 다 제공
            ));
        } catch (Exception e) {
            log.error("메뉴얼 채팅방 생성 실패", e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Failed to create manual chat room: " + e.getMessage()));
        }
    }

    /**
     * [엔드포인트 4: For Debugging/Statistics]
     * 채팅 세션 통계 조회 (디버깅용)
     * GET /api/chats/stats
     */
    @GetMapping("/api/chats/stats")
    @ResponseBody
    public ResponseEntity<?> getChatStatistics() {
        try {
            Map<String, Object> stats = chatSessionService.getSessionStatistics();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("채팅 세션 통계 조회 실패", e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Failed to get chat statistics: " + e.getMessage()));
        }
    }
}