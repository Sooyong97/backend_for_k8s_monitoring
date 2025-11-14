package com.k8s.agent.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

// 백엔드 -> 프론트엔드로 보낼 AI 답변
@Data
@AllArgsConstructor
public class AiMessage {
    private String content;
    private String author; // "ai"
}