package com.k8s.agent.backend.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
                      @Value("${loki.username:}") String username,
                      @Value("${loki.password:}") String password,
                      @Value("${loki.api-key:}") String apiKey) {
        this.baseUrl = baseUrl;
        
        // WebClient에 인증 헤더 추가
        WebClient.Builder clientBuilder = builder.baseUrl(baseUrl);
        
        // Basic Auth (username/password가 제공된 경우)
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            String credentials = username + ":" + password;
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            clientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedCredentials);
            log.info("LokiClient 초기화 완료. Base URL: {}, Basic Auth 사용", baseUrl);
        }
        // API Key (api-key가 제공된 경우)
        else if (StringUtils.hasText(apiKey)) {
            clientBuilder.defaultHeader("X-API-Key", apiKey);
            log.info("LokiClient 초기화 완료. Base URL: {}, API Key 사용", baseUrl);
        }
        // 인증 없음
        else {
            log.info("LokiClient 초기화 완료. Base URL: {} (인증 없음)", baseUrl);
        }
        
        this.webClient = clientBuilder.build();
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
                    .timeout(Duration.ofSeconds(10))
                    .doOnError(error -> log.error("Loki query 실패: {}", logql, error));
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
                    .doOnError(error -> log.error("Loki range query 실패: {}", logql, error));
        } catch (Exception e) {
            log.error("LogQL 인코딩 실패: {}", logql, e);
            return reactor.core.publisher.Mono.error(e);
        }
    }
}

