package com.k8s.agent.backend.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Loki API 클라이언트
 * LogQL 쿼리를 실행하고 JSON으로 변환하여 반환
 */
@Component
public class LokiClient {

    private static final Logger log = LoggerFactory.getLogger(LokiClient.class);
    private final WebClient webClient;
    private final String baseUrl;

    public LokiClient(WebClient.Builder builder, 
                      @Value("${loki.base-url}") String baseUrl,
                      @Value("${loki.scope-org-id:fake}") String scopeOrgId) {
        this.baseUrl = baseUrl;
        
        // X-Scope-OrgID 헤더 추가 (Loki 멀티 테넌시용)
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("X-Scope-OrgID", scopeOrgId)
                .build();
        
        log.info("LokiClient 초기화 완료. Base URL: {}, X-Scope-OrgID: {}", baseUrl, scopeOrgId);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LokiResponse {
        private String status;
        private LokiData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class LokiData {
            private String resultType;
            private List<LokiResult> result;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class LokiResult {
                private Map<String, String> stream;
                private List<List<String>> values; // [[timestamp, log_line], ...]
            }
        }
    }

    /**
     * Instant Query 실행
     */
    public Mono<LokiResponse> query(String logql) {
        log.debug("Loki instant query: {}", logql);
        try {
            // LogQL을 URL 인코딩하여 직접 URI 구성 (중괄호가 URI 템플릿 변수로 해석되는 것을 방지)
            String encodedQuery = java.net.URLEncoder.encode(logql, StandardCharsets.UTF_8)
                    .replace("+", "%20"); // +를 %20으로 변환
            URI uri = URI.create(baseUrl + "/loki/api/v1/query?query=" + encodedQuery);
            return webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(LokiResponse.class)
                    .timeout(Duration.ofSeconds(30)) // 10초 → 30초로 증가
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .filter(throwable -> {
                                String errorMsg = throwable.getMessage();
                                return errorMsg != null && (
                                    errorMsg.contains("timeout") ||
                                    errorMsg.contains("Timeout") ||
                                    throwable instanceof java.util.concurrent.TimeoutException
                                );
                            })
                            .doBeforeRetry(retrySignal ->
                                log.debug("Loki query 재시도: {}, attempt={}", logql, retrySignal.totalRetries() + 1)
                            )
                    )
                    .doOnError(error -> {
                        String errorMsg = error.getMessage();
                        if (errorMsg != null && errorMsg.contains("Connection refused")) {
                            log.warn("Loki 서버 연결 실패 (연결 거부): {}", baseUrl);
                        } else if (errorMsg != null && (errorMsg.contains("timeout") || errorMsg.contains("Timeout"))) {
                            log.warn("Loki query 타임아웃: {}", logql);
                        } else {
                            log.error("Loki query 실패: {}, error={}", logql, 
                                    errorMsg != null ? errorMsg : error.getClass().getSimpleName());
                        }
                    });
        } catch (Exception e) {
            log.error("LogQL 인코딩 실패: {}", logql, e);
            return reactor.core.publisher.Mono.error(e);
        }
    }

    /**
     * Range Query 실행
     */
    public Mono<LokiResponse> queryRange(String logql, long startSeconds, long endSeconds) {
        log.debug("Loki range query: {} (start={}, end={})", logql, startSeconds, endSeconds);
        try {
            // LogQL을 URL 인코딩하여 직접 URI 구성 (중괄호가 URI 템플릿 변수로 해석되는 것을 방지)
            String encodedQuery = java.net.URLEncoder.encode(logql, StandardCharsets.UTF_8)
                    .replace("+", "%20"); // +를 %20으로 변환
            URI uri = URI.create(baseUrl + "/loki/api/v1/query_range?query=" + encodedQuery 
                    + "&start=" + startSeconds 
                    + "&end=" + endSeconds);
            return webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(LokiResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .filter(throwable -> {
                                String errorMsg = throwable.getMessage();
                                return errorMsg != null && (
                                    errorMsg.contains("timeout") ||
                                    errorMsg.contains("Timeout") ||
                                    throwable instanceof java.util.concurrent.TimeoutException
                                );
                            })
                            .doBeforeRetry(retrySignal ->
                                log.debug("Loki range query 재시도: {}, attempt={}", logql, retrySignal.totalRetries() + 1)
                            )
                    )
                    .doOnError(error -> {
                        String errorMsg = error.getMessage();
                        if (errorMsg != null && errorMsg.contains("Connection refused")) {
                            log.warn("Loki 서버 연결 실패 (연결 거부): {}", baseUrl);
                        } else if (errorMsg != null && (errorMsg.contains("timeout") || errorMsg.contains("Timeout"))) {
                            log.warn("Loki range query 타임아웃: {}", logql);
                        } else {
                            log.error("Loki range query 실패: {}, error={}", logql, 
                                    errorMsg != null ? errorMsg : error.getClass().getSimpleName());
                        }
                    });
        } catch (Exception e) {
            log.error("LogQL 인코딩 실패: {}", logql, e);
            return reactor.core.publisher.Mono.error(e);
        }
    }
}

