package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.client.LokiClient;
import com.k8s.agent.backend.client.PrometheusClient;
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

    // 활성 구독자 추적
    private final Map<String, Boolean> activeSubscriptions = new ConcurrentHashMap<>();

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
     * 10초마다 Node 메트릭 스트림 전송
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

