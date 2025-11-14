package com.k8s.agent.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

// 프론트엔드에 "이 ID로 새 방을 만들어라"고 알릴 때 사용할 DTO
@Data
@AllArgsConstructor
public class NewChatRoomNotification {
    private String roomId;
    private AgentAnalysisResponse initialMessage; // AI의 첫 분석 결과
}