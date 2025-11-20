package com.k8s.agent.backend.controller;

import com.k8s.agent.backend.client.LokiClient;
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
 * 로그 조회 API 컨트롤러
 * Loki를 통해 로그를 조회
 */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private static final Logger log = LoggerFactory.getLogger(LogsController.class);

    @Autowired
    private LokiClient lokiClient;

    /**
     * 실시간 로그 검색 (Instant Query)
     */
    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> queryLogs(@RequestParam String query) {
        try {
            log.debug("로그 쿼리 요청: {}", query);
            
            return lokiClient.query(query)
                    .map(response -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", convertLokiToSimpleFormat(response));
                        return ResponseEntity.ok(result);
                    })
                    .block();
        } catch (Exception e) {
            log.error("로그 쿼리 실패: {}", query, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 로그 범위 조회 (Query Range)
     */
    @GetMapping("/range")
    public ResponseEntity<Map<String, Object>> queryLogsRange(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int minutes) {
        try {
            log.debug("로그 범위 쿼리 요청: {} ({}분)", query, minutes);
            
            long end = Instant.now().getEpochSecond();
            long start = end - (minutes * 60);
            
            return lokiClient.queryRange(query, start, end)
                    .map(response -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "success");
                        result.put("data", convertLokiToSimpleFormat(response));
                        return ResponseEntity.ok(result);
                    })
                    .block();
        } catch (Exception e) {
            log.error("로그 범위 쿼리 실패: {}", query, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
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

