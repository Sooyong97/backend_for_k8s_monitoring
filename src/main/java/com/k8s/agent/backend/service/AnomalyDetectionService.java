package com.k8s.agent.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 이상탐지 서비스
 * AWS, GCP, OCI, Kubernetes HPA 산업 표준 기준에 따른 이상탐지
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    @Autowired
    private MetricBaselineService baselineService;

    // 이상탐지 심각도
    public enum Severity {
        NORMAL,
        WARNING,
        CRITICAL
    }

    // Threshold 설정
    private static final double CPU_WARNING_THRESHOLD = 70.0;
    private static final double CPU_CRITICAL_THRESHOLD = 90.0;
    
    private static final double MEMORY_WARNING_THRESHOLD = 75.0;
    private static final double MEMORY_CRITICAL_THRESHOLD = 90.0;
    
    // Temperature thresholds (향후 사용 가능)
    @SuppressWarnings("unused")
    private static final double TEMPERATURE_WARNING_THRESHOLD = 70.0;
    @SuppressWarnings("unused")
    private static final double TEMPERATURE_CRITICAL_THRESHOLD = 80.0;
    
    // 증폭률 기반 threshold (baseline 대비 배수)
    private static final double AMPLIFICATION_WARNING_MULTIPLIER = 2.0;
    private static final double AMPLIFICATION_CRITICAL_MULTIPLIER = 3.0;

    /**
     * 메트릭 이상탐지 수행
     * @param node 노드 식별자
     * @param metricName 메트릭 이름
     * @param value 현재 메트릭 값
     * @return 이상탐지 결과 (severity, message)
     */
    public AnomalyResult detectAnomaly(String node, String metricName, double value) {
        // baseline 계산 (히스토리에 값 추가)
        Double baseline = baselineService.addMetricAndGetBaseline(node, metricName, value);
        
        // 메트릭 타입에 따라 다른 이상탐지 방식 적용
        switch (metricName) {
            case "cpu_usage_percent":
                return detectAbsoluteThreshold(value, CPU_WARNING_THRESHOLD, CPU_CRITICAL_THRESHOLD, "CPU 사용률");
            
            case "memory_usage_percent":
                return detectAbsoluteThreshold(value, MEMORY_WARNING_THRESHOLD, MEMORY_CRITICAL_THRESHOLD, "Memory 사용률");
            
            case "disk_read_iops":
            case "disk_write_iops":
            case "disk_read_bytes":
            case "disk_write_bytes":
                return detectAmplification(node, metricName, value, baseline, "Disk I/O");
            
            case "net_rx_bytes":
            case "net_tx_bytes":
                return detectAmplification(node, metricName, value, baseline, "Network");
            
            case "net_rx_errors":
            case "net_tx_errors":
            case "net_dropped":
            case "context_switches":
            case "packet_drops":
            case "tcp_retransmits":
                return detectAmplification(node, metricName, value, baseline, "시스템 에러");
            
            default:
                log.warn("알 수 없는 메트릭: {}", metricName);
                return new AnomalyResult(Severity.NORMAL, null);
        }
    }

    /**
     * 절대 threshold 기반 이상탐지 (CPU, Memory, Temperature)
     */
    private AnomalyResult detectAbsoluteThreshold(double value, double warningThreshold, double criticalThreshold, String metricLabel) {
        // 0값 체크 (너무 낮아도 이상)
        if (value == 0.0) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("%s가 0%%입니다. 프로세스 멈춤 또는 메트릭 수집 중단 가능성이 있습니다.", metricLabel));
        }
        
        if (value >= criticalThreshold) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("%s가 %.2f%%로 Critical 임계값(%.2f%%)을 초과했습니다.", metricLabel, value, criticalThreshold));
        }
        
        if (value >= warningThreshold) {
            return new AnomalyResult(Severity.WARNING, 
                String.format("%s가 %.2f%%로 Warning 임계값(%.2f%%)을 초과했습니다.", metricLabel, value, warningThreshold));
        }
        
        return new AnomalyResult(Severity.NORMAL, null);
    }

    /**
     * 증폭률 기반 이상탐지 (Disk I/O, Network)
     */
    private AnomalyResult detectAmplification(String node, String metricName, double value, Double baseline, String metricLabel) {
        // 0값 체크 (너무 낮아도 이상)
        if (value == 0.0 && baseline != null && baseline > 0.0) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("%s(%s)가 0으로 급락했습니다. baseline(%.2f) 대비 연결 끊김 또는 I/O block 가능성이 있습니다.", 
                    metricLabel, metricName, baseline));
        }
        
        // baseline이 없으면 이상탐지 불가 (히스토리 부족)
        if (baseline == null || baseline == 0.0) {
            return new AnomalyResult(Severity.NORMAL, null);
        }
        
        // 증폭률 계산
        double amplification = value / baseline;
        
        if (amplification >= AMPLIFICATION_CRITICAL_MULTIPLIER) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("%s(%s)가 baseline(%.2f) 대비 %.2f배(%.2f)로 급증했습니다. Critical 임계값(%.2f배) 초과.", 
                    metricLabel, metricName, baseline, amplification, value, AMPLIFICATION_CRITICAL_MULTIPLIER));
        }
        
        if (amplification >= AMPLIFICATION_WARNING_MULTIPLIER) {
            return new AnomalyResult(Severity.WARNING, 
                String.format("%s(%s)가 baseline(%.2f) 대비 %.2f배(%.2f)로 증가했습니다. Warning 임계값(%.2f배) 초과.", 
                    metricLabel, metricName, baseline, amplification, value, AMPLIFICATION_WARNING_MULTIPLIER));
        }
        
        return new AnomalyResult(Severity.NORMAL, null);
    }

    /**
     * 이상탐지 결과 클래스
     */
    public static class AnomalyResult {
        private final Severity severity;
        private final String message;

        public AnomalyResult(Severity severity, String message) {
            this.severity = severity;
            this.message = message;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        public boolean isAnomaly() {
            return severity != Severity.NORMAL;
        }
    }
}

