package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.client.PrometheusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Node 메트릭 API 컨트롤러
 * 프론트엔드가 PromQL을 직접 다루지 않고, 간단한 엔드포인트로 메트릭을 조회
 */
@RestController
@RequestMapping("/api/metrics/node")
public class MetricsController {

    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

    @Autowired
    private PrometheusClient prometheusClient;

    /**
     * CPU 사용률 조회
     */
    @GetMapping("/cpu")
    public ResponseEntity<Map<String, Object>> getCpu() {
        try {
            // node_cpu_seconds_total을 사용하여 CPU 사용률 계산
            String promql = "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)";
            
            return prometheusClient.query(promql)
                    .map(response -> {
                        List<Map<String, Object>> data = convertToSimpleFormat(response);
                        
                        // 데이터가 비어있으면 경고
                        if (data.isEmpty()) {
                            log.warn("CPU 메트릭 데이터가 비어있습니다. Prometheus 서버 상태를 확인하세요.");
                        }
                        
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", data);
                        return ResponseEntity.ok(result);
                    })
                    .block();
        } catch (Exception e) {
            log.error("CPU 메트릭 조회 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * CPU 사용률 범위 조회 (그래프용)
     */
    @GetMapping("/cpu/range")
    public ResponseEntity<Map<String, Object>> getCpuRange(@RequestParam(defaultValue = "5") int minutes) {
        try {
            String promql = "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)";
            long end = Instant.now().getEpochSecond();
            long start = end - (minutes * 60);
            int step = Math.max(1, minutes / 10); // 최소 1초, 최대 minutes/10초 간격

            return prometheusClient.queryRange(promql, start, end, step)
                    .map(response -> {
                        List<Map<String, Object>> data = convertRangeToSimpleFormat(response);
                        
                        // 데이터가 비어있으면 경고 로그
                        if (data.isEmpty()) {
                            log.warn("CPU 범위 메트릭 데이터가 비어있습니다. Prometheus 서버 상태를 확인하세요.");
                        }
                        
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", data);
                        return ResponseEntity.ok(result);
                    })
                    .block();
        } catch (Exception e) {
            log.error("CPU 범위 메트릭 조회 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Memory 사용률 조회
     */
    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> getMemory() {
        try {
            String promql = "(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100";
            
            return prometheusClient.query(promql)
                    .map(response -> {
                        List<Map<String, Object>> data = convertToSimpleFormat(response);
                        
                        // 데이터가 비어있으면 경고
                        if (data.isEmpty()) {
                            log.warn("CPU 메트릭 데이터가 비어있습니다. Prometheus 서버 상태를 확인하세요.");
                        }
                        
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", data);
                        return ResponseEntity.ok(result);
                    })
                    .block();
        } catch (Exception e) {
            log.error("Memory 메트릭 조회 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Memory 사용률 범위 조회
     */
    @GetMapping("/memory/range")
    public ResponseEntity<Map<String, Object>> getMemoryRange(@RequestParam(defaultValue = "5") int minutes) {
        try {
            String promql = "(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100";
            long end = Instant.now().getEpochSecond();
            long start = end - (minutes * 60);
            int step = Math.max(1, minutes / 10);

            return prometheusClient.queryRange(promql, start, end, step)
                    .map(response -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", convertRangeToSimpleFormat(response));
                        return ResponseEntity.ok(result);
                    })
                    .block();
        } catch (Exception e) {
            log.error("Memory 범위 메트릭 조회 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Disk 사용률 조회
     */
    @GetMapping("/disk")
    public ResponseEntity<Map<String, Object>> getDisk() {
        try {
            String promql = "(1 - (node_filesystem_avail_bytes{mountpoint=\"/\"} / node_filesystem_size_bytes{mountpoint=\"/\"})) * 100";
            
            return prometheusClient.query(promql)
                    .map(response -> {
                        List<Map<String, Object>> data = convertToSimpleFormat(response);
                        
                        // 데이터가 비어있으면 경고
                        if (data.isEmpty()) {
                            log.warn("CPU 메트릭 데이터가 비어있습니다. Prometheus 서버 상태를 확인하세요.");
                        }
                        
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", data);
                        return ResponseEntity.ok(result);
                    })
                    .block();
        } catch (Exception e) {
            log.error("Disk 메트릭 조회 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Filesystem 정보 조회
     */
    @GetMapping("/filesystem")
    public ResponseEntity<Map<String, Object>> getFilesystem() {
        try {
            String promql = "node_filesystem_size_bytes";
            
            return prometheusClient.query(promql)
                    .map(response -> {
                        List<Map<String, Object>> data = convertToSimpleFormat(response);
                        
                        // 데이터가 비어있으면 경고
                        if (data.isEmpty()) {
                            log.warn("CPU 메트릭 데이터가 비어있습니다. Prometheus 서버 상태를 확인하세요.");
                        }
                        
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", data);
                        return ResponseEntity.ok(result);
                    })
                    .block();
        } catch (Exception e) {
            log.error("Filesystem 메트릭 조회 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Network 트래픽 조회
     */
    @GetMapping("/network")
    public ResponseEntity<Map<String, Object>> getNetwork() {
        try {
            // received와 transmitted 두 개의 메트릭을 조회
            String receivedPromql = "sum by (instance) (rate(node_network_receive_bytes_total{device!=\"lo\"}[5m]))";
            String transmittedPromql = "sum by (instance) (rate(node_network_transmit_bytes_total{device!=\"lo\"}[5m]))";
            
            // 두 쿼리를 병렬로 실행
            var receivedResponse = prometheusClient.query(receivedPromql).block();
            var transmittedResponse = prometheusClient.query(transmittedPromql).block();
            
            // 결과를 합치고 direction 라벨 추가
            List<Map<String, Object>> allData = new java.util.ArrayList<>();
            
            if (receivedResponse != null && receivedResponse.getData() != null && receivedResponse.getData().getResult() != null) {
                receivedResponse.getData().getResult().forEach(result -> {
                    Map<String, Object> item = new HashMap<>();
                    Map<String, String> metric = new HashMap<>(result.getMetric());
                    metric.put("direction", "received");
                    item.put("metric", metric);
                    if (result.getValue() != null && result.getValue().size() >= 2) {
                        item.put("timestamp", result.getValue().get(0));
                        item.put("value", result.getValue().get(1));
                    }
                    allData.add(item);
                });
            }
            
            if (transmittedResponse != null && transmittedResponse.getData() != null && transmittedResponse.getData().getResult() != null) {
                transmittedResponse.getData().getResult().forEach(result -> {
                    Map<String, Object> item = new HashMap<>();
                    Map<String, String> metric = new HashMap<>(result.getMetric());
                    metric.put("direction", "transmitted");
                    item.put("metric", metric);
                    if (result.getValue() != null && result.getValue().size() >= 2) {
                        item.put("timestamp", result.getValue().get(0));
                        item.put("value", result.getValue().get(1));
                    }
                    allData.add(item);
                });
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("data", allData);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Network 메트릭 조회 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Network 트래픽 범위 조회
     */
    @GetMapping("/network/range")
    public ResponseEntity<Map<String, Object>> getNetworkRange(@RequestParam(defaultValue = "5") int minutes) {
        try {
            // received와 transmitted 두 개의 메트릭을 조회
            String receivedPromql = "sum by (instance) (rate(node_network_receive_bytes_total{device!=\"lo\"}[5m]))";
            String transmittedPromql = "sum by (instance) (rate(node_network_transmit_bytes_total{device!=\"lo\"}[5m]))";
            long end = Instant.now().getEpochSecond();
            long start = end - (minutes * 60);
            // CPU/Memory와 동일한 step 계산 사용
            int step = Math.max(1, minutes / 10); // 최소 1초, 최대 minutes/10초 간격

            // 두 쿼리를 병렬로 실행
            var receivedResponse = prometheusClient.queryRange(receivedPromql, start, end, step).block();
            var transmittedResponse = prometheusClient.queryRange(transmittedPromql, start, end, step).block();
            
            // 결과를 합치고 direction 라벨 추가
            List<Map<String, Object>> allData = new java.util.ArrayList<>();
            
            if (receivedResponse != null && receivedResponse.getData() != null && receivedResponse.getData().getResult() != null) {
                receivedResponse.getData().getResult().forEach(result -> {
                    Map<String, Object> item = new HashMap<>();
                    Map<String, String> metric = new HashMap<>(result.getMetric());
                    metric.put("direction", "received");
                    item.put("metric", metric);
                    
                    // values 배열 검증 및 필터링
                    List<List<Object>> values = result.getValues();
                    if (values == null || values.isEmpty()) {
                        item.put("values", List.of());
                    } else {
                        List<List<Object>> validValues = values.stream()
                                .filter(v -> v != null && v.size() >= 2)
                                .collect(Collectors.toList());
                        item.put("values", validValues.isEmpty() ? List.of() : validValues);
                    }
                    
                    allData.add(item);
                });
            }
            
            if (transmittedResponse != null && transmittedResponse.getData() != null && transmittedResponse.getData().getResult() != null) {
                transmittedResponse.getData().getResult().forEach(result -> {
                    Map<String, Object> item = new HashMap<>();
                    Map<String, String> metric = new HashMap<>(result.getMetric());
                    metric.put("direction", "transmitted");
                    item.put("metric", metric);
                    
                    // values 배열 검증 및 필터링
                    List<List<Object>> values = result.getValues();
                    if (values == null || values.isEmpty()) {
                        item.put("values", List.of());
                    } else {
                        List<List<Object>> validValues = values.stream()
                                .filter(v -> v != null && v.size() >= 2)
                                .collect(Collectors.toList());
                        item.put("values", validValues.isEmpty() ? List.of() : validValues);
                    }
                    
                    allData.add(item);
                });
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("data", allData);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 에러 타입별로 상세한 로깅 및 메시지 생성
            String errorMessage;
            Throwable rootCause = e;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }
            
            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                org.springframework.web.reactive.function.client.WebClientResponseException webClientError = 
                        (org.springframework.web.reactive.function.client.WebClientResponseException) e;
                org.springframework.http.HttpStatusCode status = webClientError.getStatusCode();
                String responseBody = webClientError.getResponseBodyAsString();
                log.error("Network 범위 메트릭 조회 실패 - Prometheus HTTP 에러. Status: {}, Response: {}", 
                        status, responseBody, e);
                errorMessage = String.format("Prometheus 서버 응답 오류 (Status: %s)", status);
            } else if (e instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
                if (rootCause instanceof java.net.ConnectException) {
                    log.error("Network 범위 메트릭 조회 실패 - Prometheus 서버 연결 실패 (ConnectException)", e);
                    errorMessage = "Prometheus 서버에 연결할 수 없습니다. 서버가 실행 중인지 확인하세요.";
                } else if (rootCause instanceof java.net.NoRouteToHostException) {
                    log.error("Network 범위 메트릭 조회 실패 - Prometheus 서버로 라우팅 실패 (NoRouteToHostException)", e);
                    errorMessage = "Prometheus 서버로의 네트워크 경로를 찾을 수 없습니다.";
                } else {
                    log.error("Network 범위 메트릭 조회 실패 - Prometheus 네트워크 요청 실패. Root cause: {}", 
                            rootCause.getClass().getSimpleName(), e);
                    errorMessage = "Prometheus 서버와의 네트워크 통신에 실패했습니다.";
                }
            } else if (e instanceof java.util.concurrent.TimeoutException || 
                       rootCause instanceof java.util.concurrent.TimeoutException) {
                log.error("Network 범위 메트릭 조회 실패 - Prometheus 호출 타임아웃 (30초 초과)", e);
                errorMessage = "Prometheus 응답 시간이 초과되었습니다. 서버가 과부하 상태일 수 있습니다.";
            } else {
                log.error("Network 범위 메트릭 조회 실패 - 예기치 않은 에러. Error type: {}, Root cause: {}", 
                        e.getClass().getName(), rootCause.getClass().getName(), e);
                errorMessage = "메트릭 조회 중 예기치 않은 오류가 발생했습니다: " + 
                        (e.getMessage() != null ? e.getMessage() : rootCause.getClass().getSimpleName());
            }
            
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", errorMessage));
        }
    }

    /**
     * System 정보 조회 (uptime, processes 등)
     */
    @GetMapping("/system")
    public ResponseEntity<Map<String, Object>> getSystem() {
        try {
            Map<String, Object> data = new HashMap<>();
            
            // Uptime
            try {
                var uptimeResponse = prometheusClient.query("node_boot_time_seconds").block();
                if (uptimeResponse != null && uptimeResponse.getData() != null && 
                    !uptimeResponse.getData().getResult().isEmpty()) {
                    var firstResult = uptimeResponse.getData().getResult().get(0);
                    if (firstResult.getValue() != null && firstResult.getValue().size() >= 2) {
                        double bootTime = Double.parseDouble(firstResult.getValue().get(1).toString());
                        double uptime = Instant.now().getEpochSecond() - bootTime;
                        data.put("uptime", uptime);
                    }
                }
            } catch (Exception e) {
                log.warn("Uptime 조회 실패", e);
            }
            
            // Processes
            try {
                var processesResponse = prometheusClient.query("node_procs_running").block();
                if (processesResponse != null) {
                    data.put("processes", convertToSimpleFormat(processesResponse));
                }
            } catch (Exception e) {
                log.warn("Processes 조회 실패", e);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("data", data);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("System 메트릭 조회 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // Prometheus 응답을 간단한 형식으로 변환
    private List<Map<String, Object>> convertToSimpleFormat(PrometheusClient.PrometheusResponse response) {
        if (response.getData() == null || response.getData().getResult() == null) {
            return List.of();
        }
        
        return response.getData().getResult().stream()
                .map(result -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("metric", result.getMetric());
                    if (result.getValue() != null && result.getValue().size() >= 2) {
                        item.put("timestamp", result.getValue().get(0));
                        item.put("value", result.getValue().get(1));
                    }
                    return item;
                })
                .collect(Collectors.toList());
    }

    // Range 응답을 간단한 형식으로 변환
    private List<Map<String, Object>> convertRangeToSimpleFormat(PrometheusClient.PrometheusRangeResponse response) {
        if (response.getData() == null || response.getData().getResult() == null) {
            log.warn("Prometheus range 응답 데이터가 null입니다.");
            return List.of();
        }
        
        return response.getData().getResult().stream()
                .map(result -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("metric", result.getMetric());
                    
                    // values 배열 검증 및 필터링
                    List<List<Object>> values = result.getValues();
                    if (values == null || values.isEmpty()) {
                        log.warn("Metric {}의 values 배열이 비어있습니다.", result.getMetric());
                        item.put("values", List.of());
                    } else {
                        // 각 value가 [timestamp, value] 형식인지 검증하고 필터링
                        List<List<Object>> validValues = values.stream()
                                .filter(v -> v != null && v.size() >= 2)
                                .collect(Collectors.toList());
                        
                        if (validValues.isEmpty()) {
                            log.warn("Metric {}의 모든 values 항목이 유효하지 않습니다.", result.getMetric());
                            item.put("values", List.of());
                        } else {
                            item.put("values", validValues);
                        }
                    }
                    
                    return item;
                })
                .collect(Collectors.toList());
    }
}

