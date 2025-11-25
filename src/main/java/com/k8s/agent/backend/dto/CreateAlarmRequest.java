package com.k8s.agent.backend.dto;

import com.k8s.agent.backend.entity.Alarm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 알람 생성 요청 DTO
 */
@Data
public class CreateAlarmRequest {

    @NotNull(message = "type is required")
    private Alarm.AlarmType type;

    @NotNull(message = "severity is required")
    private Alarm.Severity severity;

    @NotBlank(message = "title is required")
    private String title;

    @NotBlank(message = "message is required")
    private String message;

    private String source;

    // metadata는 JSON 문자열 또는 객체 모두 받을 수 있음
    private Object metadata;

    private String node; // 노드 정보 (예: "10.0.2.131")

    private String tags; // 태그 정보 (JSON 배열 문자열)
}

