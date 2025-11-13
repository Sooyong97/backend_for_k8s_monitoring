package com.k8s.agent.backend.dto;

import lombok.Data;

/**
 * AI 에이전트의 분석 결과를 받는 표준 DTO.
 * application.properties의 'spring.jackson.property-naming-strategy=SNAKE_CASE'
 * 설정에 의해 snake_case JSON이 camelCase 필드에 매핑
 */
@Data
public class AgentAnalysisResponse {

    // JSON: status ("success" | "error")
    private String status;

    // JSON: analysis_type ("RAG" | "FALLBACK" | "NONE")
    private String analysisType;

    // JSON: service_type ("api_server", "unknown", 등)
    private String serviceType;

    // JSON: final_answer ("AI가 생성한 최종 답변")
    private String finalAnswer;

    // JSON: error_message ("에러 발생 시 내용")
    private String errorMessage;
}