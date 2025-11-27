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
    
    // Disk I/O IOPS 절대값 기준 (운영 기준)
    // 0~20 IOPS: 아주 정상, 20~100 IOPS: 약간 부하(정상), 100~500 IOPS: 서비스 로드(모니터링 필요), 500~2000 IOPS: 고부하, 2000+ IOPS: 위험
    private static final double DISK_IOPS_WARNING_THRESHOLD = 100.0;  // 서비스 로드 시작
    private static final double DISK_IOPS_CRITICAL_THRESHOLD = 500.0; // 고부하 시작
    
    // Disk I/O Bytes 절대값 기준 (MB/s 단위)
    // Idle: 0-50KB/s, 일반: 100KB/s~5MB/s(read)/10MB/s(write), 고부하: 10MB/s~80MB/s(read)/20MB/s~150MB/s(write)
    private static final double DISK_BYTES_WARNING_THRESHOLD = 20.0 * 1024 * 1024; // 20MB/s = 20,971,520 bytes/s
    private static final double DISK_BYTES_CRITICAL_THRESHOLD = 80.0 * 1024 * 1024; // 80MB/s = 83,886,080 bytes/s
    
    // Disk Bytes 증폭률 기반 threshold (baseline 대비 배수)
    private static final double DISK_BYTES_WARNING_MULTIPLIER = 3.0;  // baseline 대비 3배
    private static final double DISK_BYTES_CRITICAL_MULTIPLIER = 5.0; // baseline 대비 5배
    
    // 증폭률 기반 threshold (baseline 대비 배수) - 일반 메트릭용
    private static final double AMPLIFICATION_WARNING_MULTIPLIER = 2.0;
    private static final double AMPLIFICATION_CRITICAL_MULTIPLIER = 3.0;
    
    // Network Bytes 절대값 기준 (MB/s 단위)
    // Idle: RX 1KB/s~10KB/s, TX 10KB/s~60KB/s
    // Moderate: RX 100KB/s~5MB/s
    // Heavy: RX 10MB/s~100MB/s+
    private static final double NET_BYTES_WARNING_THRESHOLD = 5.0 * 1024 * 1024;  // 5MB/s = 5,242,880 bytes/s
    private static final double NET_BYTES_CRITICAL_THRESHOLD = 50.0 * 1024 * 1024; // 50MB/s = 52,428,800 bytes/s
    
    // Network Bytes 증폭률 기반 threshold (baseline 대비 배수)
    private static final double NET_BYTES_WARNING_MULTIPLIER = 2.0;  // baseline 대비 2배
    private static final double NET_BYTES_CRITICAL_MULTIPLIER = 3.0; // baseline 대비 3배
    
    // Context Switches 절대값 기준
    // Warning: baseline × 3 초과 AND 절대값 > 20,000/s
    // Critical: 절대값 > 40,000/s
    private static final double CONTEXT_SWITCHES_WARNING_THRESHOLD = 20000.0;  // 20,000/s
    private static final double CONTEXT_SWITCHES_CRITICAL_THRESHOLD = 40000.0;  // 40,000/s
    private static final double CONTEXT_SWITCHES_WARNING_MULTIPLIER = 3.0;     // baseline 대비 3배
    
    // Network dropped 최소 절대 threshold (0.1 이하는 정상)
    private static final double NET_DROPPED_MIN_THRESHOLD = 0.1;
    
    // baseline이 0일 때 절대값 기준 threshold (1 미만이면 정상)
    private static final double ZERO_BASELINE_ABSOLUTE_THRESHOLD = 1.0;

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
                return detectDiskIOPSThreshold(value, metricName);
            
            case "disk_read_bytes":
            case "disk_write_bytes":
                return detectDiskBytesThreshold(node, metricName, value, baseline);
            
            case "net_rx_bytes":
            case "net_tx_bytes":
                return detectNetworkAbsoluteThreshold(value, metricName, baseline);
            
            case "net_dropped":
                return detectNetDroppedThreshold(value, baseline);
            
            case "context_switches":
                return detectContextSwitchesThreshold(value, baseline);
            
            case "net_rx_errors":
            case "net_tx_errors":
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
     * Disk IOPS 절대값 기반 이상탐지
     * 기준: 0~20 IOPS(정상), 20~100 IOPS(정상), 100~500 IOPS(모니터링 필요), 500~2000 IOPS(고부하), 2000+ IOPS(위험)
     */
    private AnomalyResult detectDiskIOPSThreshold(double value, String metricName) {
        // 0값은 정상 (아주 정상 범주)
        if (value == 0.0) {
            return new AnomalyResult(Severity.NORMAL, null);
        }
        
        if (value >= DISK_IOPS_CRITICAL_THRESHOLD) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("Disk I/O(%s)가 %.2f IOPS로 고부하 상태입니다. Critical 임계값(%.2f IOPS)을 초과했습니다. VM 성능 제한 가능성이 있습니다.", 
                    metricName, value, DISK_IOPS_CRITICAL_THRESHOLD));
        }
        
        if (value >= DISK_IOPS_WARNING_THRESHOLD) {
            return new AnomalyResult(Severity.WARNING, 
                String.format("Disk I/O(%s)가 %.2f IOPS로 서비스 로드 상태입니다. Warning 임계값(%.2f IOPS)을 초과했습니다. 모니터링이 필요합니다.", 
                    metricName, value, DISK_IOPS_WARNING_THRESHOLD));
        }
        
        return new AnomalyResult(Severity.NORMAL, null);
    }

    /**
     * Network Bytes 절대값 + 증폭률 기반 이상탐지
     * Warning: baseline 대비 2배 이상 + 절대값 > 5MB/s
     * Critical: 절대값 > 50MB/s OR baseline 대비 3배 이상
     */
    private AnomalyResult detectNetworkAbsoluteThreshold(double value, String metricName, Double baseline) {
        // 절대값 기준 Critical 체크 (50MB/s 초과)
        if (value > NET_BYTES_CRITICAL_THRESHOLD) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("Network(%s)가 %.2f MB/s로 Critical 임계값(%.2f MB/s)을 초과했습니다. 고부하 상태입니다.", 
                    metricName, value / (1024 * 1024), NET_BYTES_CRITICAL_THRESHOLD / (1024 * 1024)));
        }
        
        // baseline이 0이거나 null인 경우 절대값 기준으로 전환
        if (baseline == null || baseline == 0.0) {
            // baseline == 0 and current < 1KB/s: skip anomaly
            if (value < 1.0 * 1024) { // 1KB/s
                return new AnomalyResult(Severity.NORMAL, null);
            }
            // baseline이 0이고 값이 5MB/s 이상이면 Warning (절대값 기준)
            if (value >= NET_BYTES_WARNING_THRESHOLD) {
                return new AnomalyResult(Severity.WARNING, 
                    String.format("Network(%s)가 %.2f MB/s로 증가했습니다. baseline이 0이므로 절대값 기준으로 모니터링합니다.", 
                        metricName, value / (1024 * 1024)));
            }
            return new AnomalyResult(Severity.NORMAL, null);
        }
        
        // 증폭률 계산
        double amplification = value / baseline;
        
        // Critical: baseline 대비 3배 이상
        if (amplification >= NET_BYTES_CRITICAL_MULTIPLIER) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("Network(%s)가 baseline(%.2f MB/s) 대비 %.2f배(%.2f MB/s)로 급증했습니다. Critical 임계값(%.2f배) 초과.", 
                    metricName, baseline / (1024 * 1024), amplification, value / (1024 * 1024), NET_BYTES_CRITICAL_MULTIPLIER));
        }
        
        // Warning: baseline 대비 2배 이상 AND 절대값 > 5MB/s
        if (amplification >= NET_BYTES_WARNING_MULTIPLIER && value > NET_BYTES_WARNING_THRESHOLD) {
            return new AnomalyResult(Severity.WARNING, 
                String.format("Network(%s)가 baseline(%.2f MB/s) 대비 %.2f배(%.2f MB/s)로 증가했습니다. Warning 임계값(%.2f배, %.2f MB/s) 초과.", 
                    metricName, baseline / (1024 * 1024), amplification, value / (1024 * 1024), 
                    NET_BYTES_WARNING_MULTIPLIER, NET_BYTES_WARNING_THRESHOLD / (1024 * 1024)));
        }
        
        return new AnomalyResult(Severity.NORMAL, null);
    }

    /**
     * net_dropped 절대값 기반 이상탐지 (최소 threshold 적용)
     */
    private AnomalyResult detectNetDroppedThreshold(double value, Double baseline) {
        // 최소 절대 threshold: 0.1 이하는 정상 (노이즈 방지)
        if (value < NET_DROPPED_MIN_THRESHOLD) {
            return new AnomalyResult(Severity.NORMAL, null);
        }
        
        // baseline이 0이거나 null인 경우 절대값 기준으로 전환
        if (baseline == null || baseline == 0.0) {
            // baseline == 0 and current < 1: skip anomaly
            if (value < ZERO_BASELINE_ABSOLUTE_THRESHOLD) {
                return new AnomalyResult(Severity.NORMAL, null);
            }
            // baseline이 0이고 값이 1 이상이면 Warning (절대값 기준)
            return new AnomalyResult(Severity.WARNING, 
                String.format("Network dropped가 %.2f로 증가했습니다. baseline이 0이므로 절대값 기준으로 모니터링합니다.", value));
        }
        
        // baseline이 있는 경우 증폭률 기반 이상탐지
        double amplification = value / baseline;
        
        if (amplification >= AMPLIFICATION_CRITICAL_MULTIPLIER) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("Network dropped가 baseline(%.2f) 대비 %.2f배(%.2f)로 급증했습니다. Critical 임계값(%.2f배) 초과.", 
                    baseline, amplification, value, AMPLIFICATION_CRITICAL_MULTIPLIER));
        }
        
        if (amplification >= AMPLIFICATION_WARNING_MULTIPLIER) {
            return new AnomalyResult(Severity.WARNING, 
                String.format("Network dropped가 baseline(%.2f) 대비 %.2f배(%.2f)로 증가했습니다. Warning 임계값(%.2f배) 초과.", 
                    baseline, amplification, value, AMPLIFICATION_WARNING_MULTIPLIER));
        }
        
        return new AnomalyResult(Severity.NORMAL, null);
    }

    /**
     * Disk Bytes 절대값 + 증폭률 기반 이상탐지
     * Warning: baseline 대비 3배 이상 + 절대값 >= 20MB/s
     * Critical: 절대값 > 80MB/s OR baseline 대비 5배 이상
     */
    private AnomalyResult detectDiskBytesThreshold(String node, String metricName, double value, Double baseline) {
        // 절대값 기준 Critical 체크 (80MB/s 초과)
        if (value > DISK_BYTES_CRITICAL_THRESHOLD) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("Disk I/O(%s)가 %.2f MB/s로 Critical 임계값(%.2f MB/s)을 초과했습니다. 고부하 상태입니다.", 
                    metricName, value / (1024 * 1024), DISK_BYTES_CRITICAL_THRESHOLD / (1024 * 1024)));
        }
        
        // baseline이 0이거나 null인 경우 절대값 기준으로 전환
        if (baseline == null || baseline == 0.0) {
            // baseline == 0 and current < 1MB/s: skip anomaly
            if (value < 1.0 * 1024 * 1024) { // 1MB/s
                return new AnomalyResult(Severity.NORMAL, null);
            }
            // baseline이 0이고 값이 1MB/s 이상이면 절대값 기준으로 체크
            if (value >= DISK_BYTES_WARNING_THRESHOLD) {
                return new AnomalyResult(Severity.WARNING, 
                    String.format("Disk I/O(%s)가 %.2f MB/s로 증가했습니다. baseline이 0이므로 절대값 기준으로 모니터링합니다.", 
                        metricName, value / (1024 * 1024)));
            }
            return new AnomalyResult(Severity.NORMAL, null);
        }
        
        // 증폭률 계산
        double amplification = value / baseline;
        
        // Critical: baseline 대비 5배 이상
        if (amplification >= DISK_BYTES_CRITICAL_MULTIPLIER) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("Disk I/O(%s)가 baseline(%.2f MB/s) 대비 %.2f배(%.2f MB/s)로 급증했습니다. Critical 임계값(%.2f배) 초과.", 
                    metricName, baseline / (1024 * 1024), amplification, value / (1024 * 1024), DISK_BYTES_CRITICAL_MULTIPLIER));
        }
        
        // Warning: baseline 대비 3배 이상 AND 절대값 >= 20MB/s
        if (amplification >= DISK_BYTES_WARNING_MULTIPLIER && value >= DISK_BYTES_WARNING_THRESHOLD) {
            return new AnomalyResult(Severity.WARNING, 
                String.format("Disk I/O(%s)가 baseline(%.2f MB/s) 대비 %.2f배(%.2f MB/s)로 증가했습니다. Warning 임계값(%.2f배, %.2f MB/s) 초과.", 
                    metricName, baseline / (1024 * 1024), amplification, value / (1024 * 1024), 
                    DISK_BYTES_WARNING_MULTIPLIER, DISK_BYTES_WARNING_THRESHOLD / (1024 * 1024)));
        }
        
        return new AnomalyResult(Severity.NORMAL, null);
    }

    /**
     * Context Switches 절대값 + 증폭률 기반 이상탐지
     * Warning: baseline × 3 초과 AND 절대값 > 20,000/s
     * Critical: 절대값 > 40,000/s
     */
    private AnomalyResult detectContextSwitchesThreshold(double value, Double baseline) {
        // 절대값 기준 Critical 체크 (40,000/s 초과)
        if (value > CONTEXT_SWITCHES_CRITICAL_THRESHOLD) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("Context Switches가 %.0f/s로 Critical 임계값(%.0f/s)을 초과했습니다.", 
                    value, CONTEXT_SWITCHES_CRITICAL_THRESHOLD));
        }
        
        // baseline이 0이거나 null인 경우 절대값 기준으로 전환
        if (baseline == null || baseline == 0.0) {
            // baseline == 0 and current < 1000: skip anomaly
            if (value < 1000.0) {
                return new AnomalyResult(Severity.NORMAL, null);
            }
            // baseline이 0이고 값이 20,000/s 이상이면 Warning (절대값 기준)
            if (value >= CONTEXT_SWITCHES_WARNING_THRESHOLD) {
                return new AnomalyResult(Severity.WARNING, 
                    String.format("Context Switches가 %.0f/s로 증가했습니다. baseline이 0이므로 절대값 기준으로 모니터링합니다.", value));
            }
            return new AnomalyResult(Severity.NORMAL, null);
        }
        
        // 증폭률 계산
        double amplification = value / baseline;
        
        // Warning: baseline 대비 3배 이상 AND 절대값 > 20,000/s
        if (amplification >= CONTEXT_SWITCHES_WARNING_MULTIPLIER && value > CONTEXT_SWITCHES_WARNING_THRESHOLD) {
            return new AnomalyResult(Severity.WARNING, 
                String.format("Context Switches가 baseline(%.0f/s) 대비 %.2f배(%.0f/s)로 증가했습니다. Warning 임계값(%.2f배, %.0f/s) 초과.", 
                    baseline, amplification, value, CONTEXT_SWITCHES_WARNING_MULTIPLIER, CONTEXT_SWITCHES_WARNING_THRESHOLD));
        }
        
        return new AnomalyResult(Severity.NORMAL, null);
    }

    /**
     * 증폭률 기반 이상탐지 (System Errors)
     */
    private AnomalyResult detectAmplification(String node, String metricName, double value, Double baseline, String metricLabel) {
        // 0값 체크 (너무 낮아도 이상)
        if (value == 0.0 && baseline != null && baseline > 0.0) {
            return new AnomalyResult(Severity.CRITICAL, 
                String.format("%s(%s)가 0으로 급락했습니다. baseline(%.2f) 대비 연결 끊김 또는 I/O block 가능성이 있습니다.", 
                    metricLabel, metricName, baseline));
        }
        
        // baseline이 0이거나 null인 경우 절대값 기준으로 전환
        if (baseline == null || baseline == 0.0) {
            // baseline == 0 and current < 1: skip anomaly
            if (value < ZERO_BASELINE_ABSOLUTE_THRESHOLD) {
                return new AnomalyResult(Severity.NORMAL, null);
            }
            // baseline이 0이고 값이 1 이상이면 Warning (절대값 기준)
            return new AnomalyResult(Severity.WARNING, 
                String.format("%s(%s)가 %.2f로 증가했습니다. baseline이 0이므로 절대값 기준으로 모니터링합니다.", 
                    metricLabel, metricName, value));
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

