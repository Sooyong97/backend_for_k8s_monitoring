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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Prometheus API 클라이언트
 * PromQL 쿼리를 실행하고 JSON으로 변환하여 반환
 */
@Component
public class PrometheusClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);
    private final WebClient webClient;
    private final String baseUrl;

    public PrometheusClient(WebClient.Builder builder, @Value("${prometheus.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.webClient = builder
                .baseUrl(baseUrl)
                .build();
        log.info("PrometheusClient 초기화 완료. Base URL: {}", baseUrl);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrometheusResponse {
        private String status;
        private QueryData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class QueryData {
            private String resultType;
            private List<Result> result;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Result {
                private Map<String, String> metric;
                private List<Object> value; // [timestamp, value]
            }
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrometheusRangeResponse {
        private String status;
        private RangeQueryData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class RangeQueryData {
            private String resultType;
            private List<RangeResult> result;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class RangeResult {
                private Map<String, String> metric;
                private List<List<Object>> values; // [[timestamp, value], ...]
            }
        }
    }

    /**
     * Instant Query 실행
     */
    public Mono<PrometheusResponse> query(String promql) {
        log.debug("Prometheus instant query: {}", promql);
        // PromQL을 URL 인코딩하여 직접 URI 구성 (WebClient의 queryParam이 이스케이프 문자를 제대로 처리하지 못함)
        try {
            // URI를 직접 구성하여 올바른 인코딩 보장
            URI uri = URI.create(baseUrl + "/api/v1/query?query=" + 
                    java.net.URLEncoder.encode(promql, StandardCharsets.UTF_8)
                            .replace("+", "%20")); // +를 %20으로 변환 (Prometheus 호환)
            return webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(PrometheusResponse.class)
                    .timeout(Duration.ofSeconds(10))
                    .doOnError(error -> log.error("Prometheus query 실패: {}", promql, error));
        } catch (Exception e) {
            log.error("PromQL 인코딩 실패: {}", promql, e);
            return Mono.error(e);
        }
    }

    /**
     * Range Query 실행 (그래프용)
     */
    public Mono<PrometheusRangeResponse> queryRange(String promql, long startSeconds, long endSeconds, int stepSeconds) {
        log.debug("Prometheus range query: {} (start={}, end={}, step={})", promql, startSeconds, endSeconds, stepSeconds);
        // PromQL을 URL 인코딩하여 직접 URI 구성
        try {
            // URI를 직접 구성하여 올바른 인코딩 보장
            String encodedQuery = java.net.URLEncoder.encode(promql, StandardCharsets.UTF_8)
                    .replace("+", "%20"); // +를 %20으로 변환 (Prometheus 호환)
            URI uri = URI.create(baseUrl + "/api/v1/query_range?query=" + encodedQuery 
                    + "&start=" + startSeconds 
                    + "&end=" + endSeconds 
                    + "&step=" + stepSeconds + "s");
            return webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(PrometheusRangeResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .doOnError(error -> log.error("Prometheus range query 실패: {}", promql, error));
        } catch (Exception e) {
            log.error("PromQL 인코딩 실패: {}", promql, e);
            return Mono.error(e);
        }
    }
}

