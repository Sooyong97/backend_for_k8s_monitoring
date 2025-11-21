package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.client.PrometheusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 시스템 정보 API 컨트롤러
 * 정적/반정적 시스템 정보를 제공 (CPU core 수, 총 RAM, 네트워크 링크 속도 등)
 * 프론트엔드에서 refresh할 때만 호출
 */
@RestController
@RequestMapping("/api/metrics/system")
public class SystemMetricsController {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsController.class);

    @Autowired
    private PrometheusClient prometheusClient;

    /**
     * CPU Core 수 조회
     * GET /api/metrics/system/cpu_cores
     */
    @GetMapping("/cpu_cores")
    public ResponseEntity<Map<String, Object>> getCpuCores() {
        try {
            // CPU core 수: instance:node_num_cpu:sum 사용 (더 안정적)
            // fallback: count(node_cpu_seconds_total{mode="idle"}) by (instance)
            String promql = "instance:node_num_cpu:sum";
            
            var response = prometheusClient.query(promql).block();
            
            if (response != null && response.getData() != null && 
                response.getData().getResult() != null && !response.getData().getResult().isEmpty()) {
                
                var firstResult = response.getData().getResult().get(0);
                if (firstResult.getValue() != null && firstResult.getValue().size() >= 2) {
                    double cores = Double.parseDouble(firstResult.getValue().get(1).toString());
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("data", Map.of(
                        "instance", firstResult.getMetric().getOrDefault("instance", "unknown"),
                        "cpu_cores", (int) cores
                    ));
                    return ResponseEntity.ok(result);
                }
            }
            
            // Fallback: count 방식 시도
            try {
                String fallbackPromql = "count(node_cpu_seconds_total{mode=\"idle\"}) by (instance)";
                var fallbackResponse = prometheusClient.query(fallbackPromql).block();
                
                if (fallbackResponse != null && fallbackResponse.getData() != null && 
                    fallbackResponse.getData().getResult() != null && !fallbackResponse.getData().getResult().isEmpty()) {
                    
                    var firstResult = fallbackResponse.getData().getResult().get(0);
                    if (firstResult.getValue() != null && firstResult.getValue().size() >= 2) {
                        double cores = Double.parseDouble(firstResult.getValue().get(1).toString());
                        
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", Map.of(
                            "instance", firstResult.getMetric().getOrDefault("instance", "unknown"),
                            "cpu_cores", (int) cores
                        ));
                        return ResponseEntity.ok(result);
                    }
                }
            } catch (Exception e) {
                log.debug("CPU core 수 fallback 쿼리 실패", e);
            }
            
            return ResponseEntity.ok(Map.of("status", "success", "data", Map.of("cpu_cores", 0)));
        } catch (Exception e) {
            log.error("CPU core 수 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 총 RAM (GB) 조회
     * GET /api/metrics/system/memory_total_gb
     */
    @GetMapping("/memory_total_gb")
    public ResponseEntity<Map<String, Object>> getMemoryTotalGb() {
        try {
            // 총 메모리: node_memory_MemTotal_bytes (바이트 단위)
            String promql = "node_memory_MemTotal_bytes";
            
            var response = prometheusClient.query(promql).block();
            
            if (response != null && response.getData() != null && 
                response.getData().getResult() != null && !response.getData().getResult().isEmpty()) {
                
                var firstResult = response.getData().getResult().get(0);
                if (firstResult.getValue() != null && firstResult.getValue().size() >= 2) {
                    double totalBytes = Double.parseDouble(firstResult.getValue().get(1).toString());
                    double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0); // 바이트를 GB로 변환
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("data", Map.of(
                        "instance", firstResult.getMetric().get("instance"),
                        "memory_total_gb", Math.round(totalGb * 100.0) / 100.0 // 소수점 2자리
                    ));
                    return ResponseEntity.ok(result);
                }
            }
            
            return ResponseEntity.ok(Map.of("status", "success", "data", Map.of("memory_total_gb", 0)));
        } catch (Exception e) {
            log.error("총 RAM 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 네트워크 링크 속도 (Gbps) 조회
     * GET /api/metrics/system/net_speed_gbps
     */
    @GetMapping("/net_speed_gbps")
    public ResponseEntity<Map<String, Object>> getNetSpeedGbps() {
        try {
            // 네트워크 링크 속도: node_network_speed_bytes (일반적으로 bps 단위)
            // 또는 node_network_info의 speed 필드 사용
            // loopback 제외하고 첫 번째 활성 인터페이스의 속도 사용
            String promql = "node_network_speed_bytes{device!=\"lo\"}";
            
            var response = prometheusClient.query(promql).block();
            
            if (response != null && response.getData() != null && 
                response.getData().getResult() != null && !response.getData().getResult().isEmpty()) {
                
                // 첫 번째 활성 인터페이스의 속도 사용
                var firstResult = response.getData().getResult().get(0);
                if (firstResult.getValue() != null && firstResult.getValue().size() >= 2) {
                    double speedBytes = Double.parseDouble(firstResult.getValue().get(1).toString());
                    // bytes를 Gbps로 변환 (1 Gbps = 1,000,000,000 bits/s = 125,000,000 bytes/s)
                    double speedGbps = (speedBytes * 8) / 1_000_000_000.0;
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("data", Map.of(
                        "instance", firstResult.getMetric().get("instance"),
                        "device", firstResult.getMetric().get("device"),
                        "net_speed_gbps", Math.round(speedGbps * 100.0) / 100.0 // 소수점 2자리
                    ));
                    return ResponseEntity.ok(result);
                }
            }
            
            // node_network_speed_bytes가 없으면 node_network_info 시도
            try {
                String infoPromql = "node_network_info{device!=\"lo\"}";
                var infoResponse = prometheusClient.query(infoPromql).block();
                
                if (infoResponse != null && infoResponse.getData() != null && 
                    infoResponse.getData().getResult() != null && !infoResponse.getData().getResult().isEmpty()) {
                    
                    var firstResult = infoResponse.getData().getResult().get(0);
                    // node_network_info는 speed를 라벨로 가지고 있을 수 있음
                    String speedStr = firstResult.getMetric().get("speed");
                    if (speedStr != null) {
                        try {
                            // speed는 보통 Mbps 단위
                            double speedMbps = Double.parseDouble(speedStr);
                            double speedGbps = speedMbps / 1000.0;
                            
                            Map<String, Object> result = new HashMap<>();
                            result.put("status", "success");
                            result.put("data", Map.of(
                                "instance", firstResult.getMetric().get("instance"),
                                "device", firstResult.getMetric().get("device"),
                                "net_speed_gbps", Math.round(speedGbps * 100.0) / 100.0
                            ));
                            return ResponseEntity.ok(result);
                        } catch (NumberFormatException e) {
                            log.warn("네트워크 속도 파싱 실패: {}", speedStr);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("node_network_info 조회 실패, node_network_speed_bytes 사용", e);
            }
            
            return ResponseEntity.ok(Map.of("status", "success", "data", Map.of("net_speed_gbps", 0)));
        } catch (Exception e) {
            log.error("네트워크 링크 속도 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}

