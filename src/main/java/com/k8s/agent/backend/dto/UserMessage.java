package com.k8s.agent.backend.dto;

import lombok.Data;

// 프론트엔드 -> 백엔드로 보낼 사용자 메시지
@Data
public class UserMessage {
    private String content;
}