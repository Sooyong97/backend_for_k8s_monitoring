package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.client.LokiClient;
import com.k8s.agent.backend.client.PrometheusClient;
import com.k8s.agent.backend.service.AnomalyDetectionService;
import com.k8s.agent.backend.service.AnomalyNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 실시간 스트림 WebSocket 컨트롤러
 * 주기적으로 메트릭과 로그를 조회하여 WebSocket으로 전송
 */
@Controller
public class RealtimeStreamController {

    private static final Logger log = LoggerFactory.getLogger(RealtimeStreamController.class);

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private PrometheusClient prometheusClient;

    @Autowired
    private LokiClient lokiClient;

    @Autowired
    private AnomalyDetectionService anomalyDetectionService;

    @Autowired
    private AnomalyNotificationService anomalyNotificationService;

    // 활성 구독자 추적
    private final Map<String, Boolean> activeSubscriptions = new ConcurrentHashMap<>();
    
    // ERROR 로그 중복 방지를 위한 마지막 처리된 로그 추적
    // key = "stream_key:log_message_hash", value = timestamp
    private final Map<String, Long> lastProcessedLogs = new ConcurrentHashMap<>();

    /**
     * 에러 로그 실시간 스트림 구독
     * WS /ws/logs/error
     */
    @MessageMapping("/logs/error/subscribe")
    public void subscribeErrorLogs() {
        activeSubscriptions.put("logs/error", true);
        log.info("에러 로그 스트림 구독 시작");
    }

    /**
     * 전체 로그 실시간 스트림 구독
     * WS /ws/logs/stream
     */
    @MessageMapping("/logs/stream/subscribe")
    public void subscribeLogs() {
        activeSubscriptions.put("logs/stream", true);
        log.info("로그 스트림 구독 시작");
    }

    /**
     * Node 메트릭 실시간 스트림 구독
     * WS /ws/metrics/node
     */
    @MessageMapping("/metrics/node/subscribe")
    public void subscribeMetrics() {
        activeSubscriptions.put("metrics/node", true);
        log.info("메트릭 스트림 구독 시작");
    }

    /**
     * 5초마다 에러 로그 스트림 전송
     */
    @Scheduled(fixedRate = 5000)
    public void streamErrorLogs() {
        if (!activeSubscriptions.getOrDefault("logs/error", false)) {
            return;
        }

        try {
            String logql = "{level=\"error\"} | json";
            long end = Instant.now().getEpochSecond();
            long start = end - 60; // 최근 1분

            lokiClient.queryRange(logql, start, end)
                    .subscribe(response -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("timestamp", Instant.now().getEpochSecond());
                        data.put("logs", convertLokiToSimpleFormat(response));
                        messagingTemplate.convertAndSend("/topic/logs/error", data);
                    });
        } catch (Exception e) {
            log.error("에러 로그 스트림 전송 실패", e);
        }
    }

    /**
     * 10초마다 이상탐지 수행 (프론트엔드 구독 여부와 무관하게 항상 실행)
     */
    @Scheduled(fixedRate = 10000)
    public void detectAnomalies() {
        try {
            long timestamp = Instant.now().getEpochSecond();
            
            // CPU 사용률 이상탐지 (모든 라벨 유지)
            String cpuPromql = "100 - (avg by (instance, job, namespace, container, endpoint, pod, service) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)";
            collectAndDetectAnomaly(cpuPromql, "cpu_usage_percent", timestamp);
            
            // Memory 사용률 이상탐지 (모든 라벨 유지)
            String memoryPromql = "(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100";
            collectAndDetectAnomaly(memoryPromql, "memory_usage_percent", timestamp);
            
            
            // Disk Read IOPS 이상탐지 (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_disk_reads_completed_total[5m]))",
                "disk_read_iops",
                timestamp
            );
            
            // Disk Write IOPS (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_disk_writes_completed_total[5m]))",
                "disk_write_iops",
                timestamp
            );
            
            // Disk Read Bytes (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_disk_read_bytes_total[5m]))",
                "disk_read_bytes",
                timestamp
            );
            
            // Disk Write Bytes (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_disk_written_bytes_total[5m]))",
                "disk_write_bytes",
                timestamp
            );
            
            // Network RX Bytes (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_network_receive_bytes_total{device!=\"lo\"}[5m]))",
                "net_rx_bytes",
                timestamp
            );

            // Network TX Bytes (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_network_transmit_bytes_total{device!=\"lo\"}[5m]))",
                "net_tx_bytes",
                timestamp
            );

            // Network RX Errors (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_network_receive_errs_total{device!=\"lo\"}[5m]))",
                "net_rx_errors",
                timestamp
            );

            // Network TX Errors (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_network_transmit_errs_total{device!=\"lo\"}[5m]))",
                "net_tx_errors",
                timestamp
            );

            // Network Dropped (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_network_receive_drop_total{device!=\"lo\"}[5m]) + rate(node_network_transmit_drop_total{device!=\"lo\"}[5m]))",
                "net_dropped",
                timestamp
            );
            
            // Context Switches (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_context_switches_total[5m]))",
                "context_switches",
                timestamp
            );
            
            // Packet Drops (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_network_receive_packets_dropped_total{device!=\"lo\"}[5m]))",
                "packet_drops",
                timestamp
            );
            
            // TCP Retransmits (모든 라벨 유지)
            collectAndDetectAnomaly(
                "sum by (instance, job, namespace, container, endpoint, pod, service) (rate(node_sockstat_TCP_retrans[5m]))",
                "tcp_retransmits",
                timestamp
            );
            
        } catch (Exception e) {
            log.error("이상탐지 수행 실패", e);
        }
    }

    /**
     * 10분마다 ERROR 로그 감지 및 analyze 전송 (프론트엔드 구독 여부와 무관하게 항상 실행)
     */
    @Scheduled(fixedRate = 600000) // 10분 = 600,000ms
    public void detectErrorLogs() {
        try {
            long end = Instant.now().getEpochSecond();
            long start = end - 600; // 최근 10분간의 로그만 확인 (중복 방지)
            
            String logql = "{level=\"error\"} | json";
            
            log.info("ERROR 로그 수집 시작: start={}, end={}", start, end);
            lokiClient.queryRange(logql, start, end)
                    .subscribe(
                            response -> {
                                if (response.getData() != null && response.getData().getResult() != null) {
                                    int totalLogs = response.getData().getResult().size();
                                    log.info("ERROR 로그 수집 완료: 총 {}개 스트림 발견", totalLogs);
                                    
                                    int processedCount = 0;
                                    int sentCount = 0;
                                    
                                    for (LokiClient.LokiResponse.LokiData.LokiResult result : response.getData().getResult()) {
                                        try {
                                            // 원본 로그 데이터 구성
                                            Map<String, Object> logData = new HashMap<>();
                                            logData.put("stream", result.getStream());
                                            logData.put("values", result.getValues());
                                            
                                            // 각 로그 라인에 대해 처리
                                            if (result.getValues() != null) {
                                                for (List<String> logEntry : result.getValues()) {
                                                    if (logEntry != null && logEntry.size() >= 2) {
                                                        processedCount++;
                                                        String logTimestamp = logEntry.get(0);
                                                        String logMessage = logEntry.get(1);
                                                        
                                                        // 중복 체크: stream 라벨 + 로그 메시지 해시
                                                        String streamKey = result.getStream() != null 
                                                                ? result.getStream().toString() 
                                                                : "unknown";
                                                        String logHash = String.valueOf(logMessage.hashCode());
                                                        String logKey = streamKey + ":" + logHash;
                                                        
                                                        // 이미 처리한 로그인지 확인
                                                        Long lastProcessed = lastProcessedLogs.get(logKey);
                                                        long logTime;
                                                        try {
                                                            // Loki 타임스탬프는 나노초 단위 문자열 (예: "1705747800000000000")
                                                            // 나노초를 초로 변환
                                                            if (logTimestamp.length() > 10) {
                                                                logTime = Long.parseLong(logTimestamp.substring(0, 10));
                                                            } else {
                                                                logTime = Long.parseLong(logTimestamp);
                                                            }
                                                        } catch (NumberFormatException e) {
                                                            log.warn("로그 타임스탬프 파싱 실패: {}", logTimestamp);
                                                            logTime = Instant.now().getEpochSecond();
                                                        }
                                                        
                                                        if (lastProcessed == null || logTime > lastProcessed) {
                                                            // 새로운 로그이거나 더 최신 로그인 경우 전송
                                                            lastProcessedLogs.put(logKey, logTime);
                                                            
                                                            // 로그 데이터 구성 (단일 로그 항목)
                                                            Map<String, Object> singleLogData = new HashMap<>();
                                                            singleLogData.put("stream", result.getStream());
                                                            singleLogData.put("values", List.of(logEntry));
                                                            
                                                            log.info("ERROR 로그 전송 시도: logKey={}, message={}", logKey, 
                                                                    logMessage.length() > 100 ? logMessage.substring(0, 100) + "..." : logMessage);
                                                            
                                                            // analyze로 전송
                                                            anomalyNotificationService.sendErrorLogNotification(singleLogData);
                                                            sentCount++;
                                                        } else {
                                                            log.debug("중복 로그 건너뜀: logKey={}, lastProcessed={}, logTime={}", 
                                                                    logKey, lastProcessed, logTime);
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (Exception e) {
                                            log.error("ERROR 로그 처리 실패", e);
                                        }
                                    }
                                    
                                    log.info("ERROR 로그 처리 완료: 총 {}개 처리, {}개 전송", processedCount, sentCount);
                                } else {
                                    log.debug("ERROR 로그 수집 결과: 데이터 없음");
                                }
                            },
                                error -> {
                                    String errorMsg = error.getMessage();
                                    if (errorMsg != null && errorMsg.contains("Connection refused")) {
                                        log.debug("Loki 서버 연결 실패로 ERROR 로그 수집 건너뜀");
                                    } else {
                                        log.warn("ERROR 로그 수집 실패: {}", 
                                                errorMsg != null ? errorMsg : error.getClass().getSimpleName());
                                    }
                                }
                    );
        } catch (Exception e) {
            log.error("ERROR 로그 감지 실패", e);
        }
    }

    /**
     * 10초마다 Node 메트릭 스트림 전송 (구독자가 있는 경우만)
     */
    @Scheduled(fixedRate = 10000)
    public void streamNodeMetrics() {
        if (!activeSubscriptions.getOrDefault("metrics/node", false)) {
            return;
        }

        try {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("timestamp", Instant.now().getEpochSecond());

            // CPU
            prometheusClient.query("100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)")
                    .subscribe(response -> {
                        if (response.getData() != null && !response.getData().getResult().isEmpty()) {
                            var result = response.getData().getResult().get(0);
                            if (result.getValue() != null && result.getValue().size() >= 2) {
                                metrics.put("cpu", result.getValue().get(1));
                            }
                        }
                    });

            // Memory
            prometheusClient.query("(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100")
                    .subscribe(response -> {
                        if (response.getData() != null && !response.getData().getResult().isEmpty()) {
                            var result = response.getData().getResult().get(0);
                            if (result.getValue() != null && result.getValue().size() >= 2) {
                                metrics.put("memory", result.getValue().get(1));
                            }
                        }
                    });

            // Disk
            prometheusClient.query("(1 - (node_filesystem_avail_bytes{mountpoint=\"/\"} / node_filesystem_size_bytes{mountpoint=\"/\"})) * 100")
                    .subscribe(response -> {
                        if (response.getData() != null && !response.getData().getResult().isEmpty()) {
                            var result = response.getData().getResult().get(0);
                            if (result.getValue() != null && result.getValue().size() >= 2) {
                                metrics.put("disk", result.getValue().get(1));
                            }
                        }
                    });

            messagingTemplate.convertAndSend("/topic/metrics/node", metrics);
        } catch (Exception e) {
            log.error("메트릭 스트림 전송 실패", e);
        }
    }

    /**
     * 메트릭을 수집하고 이상탐지 수행
     */
    private void collectAndDetectAnomaly(String promql, String metricName, long timestamp) {
        prometheusClient.query(promql)
                .subscribe(
                        response -> {
                            if (response.getData() != null && response.getData().getResult() != null) {
                                response.getData().getResult().forEach(result -> {
                                    try {
                                        // 노드 식별자 추출
                                        String instance = result.getMetric().getOrDefault("instance", "unknown");
                                        String node = instance.contains(":") ? instance.split(":")[0] : instance;
                                        
                                        // 메트릭 값 추출
                                        if (result.getValue() != null && result.getValue().size() >= 2) {
                                            double value = Double.parseDouble(result.getValue().get(1).toString());
                                            
                                            // 이상탐지 수행
                                            AnomalyDetectionService.AnomalyResult anomalyResult = 
                                                    anomalyDetectionService.detectAnomaly(node, metricName, value);
                                            
                                            // 이상이 탐지되면 원본 메트릭 데이터를 그대로 전송
                                            if (anomalyResult.isAnomaly()) {
                                                // Prometheus 원본 메트릭 데이터 구성
                                                Map<String, Object> originalMetric = new HashMap<>();
                                                originalMetric.put("metric", result.getMetric());
                                                originalMetric.put("value", result.getValue());
                                                // 노드 정보를 최상위 레벨에 추가
                                                originalMetric.put("node", node);
                                                originalMetric.put("instance", instance);
                                                
                                                anomalyNotificationService.sendAnomalyNotification(
                                                        node,
                                                        metricName,
                                                        anomalyResult.getSeverity(),
                                                        anomalyResult.getMessage(),
                                                        originalMetric
                                                );
                                            }
                                        }
                                    } catch (Exception e) {
                                        log.error("메트릭 이상탐지 처리 실패: metric={}", metricName, e);
                                    }
                                });
                            }
                        },
                        error -> log.error("메트릭 수집 실패: metric={}", metricName, error)
                );
    }

    // Loki 응답을 간단한 형식으로 변환
    private List<Map<String, Object>> convertLokiToSimpleFormat(LokiClient.LokiResponse response) {
        if (response.getData() == null || response.getData().getResult() == null) {
            return List.of();
        }
        
        return response.getData().getResult().stream()
                .map(result -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("stream", result.getStream());
                    item.put("values", result.getValues());
                    return item;
                })
                .collect(Collectors.toList());
    }
}

