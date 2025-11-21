package com.k8s.agent.backend.dto;

import com.k8s.agent.backend.entity.Alarm;
import lombok.Data;

/**
 * 알람 업데이트 요청 DTO
 * 모든 필드가 선택적 (제공된 필드만 업데이트)
 */
@Data
public class UpdateAlarmRequest {

    private String title;

    private String message;

    private Alarm.Severity severity;

    private String source;

    // metadata는 JSON 문자열 또는 객체 모두 받을 수 있음
    private Object metadata;
}

