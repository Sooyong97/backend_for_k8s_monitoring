package com.k8s.agent.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 요청/응답 본문을 로깅하는 필터
 * /api/v1/alerts/report 엔드포인트의 요청 본문을 로깅
 */
@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // /api/v1/alerts/report 엔드포인트만 로깅
        if (request.getRequestURI().equals("/api/v1/alerts/report")) {
            ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
            ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

            try {
                filterChain.doFilter(wrappedRequest, wrappedResponse);
            } finally {
                // 요청 본문 로깅
                byte[] requestBody = wrappedRequest.getContentAsByteArray();
                if (requestBody.length > 0) {
                    String requestBodyString = new String(requestBody, StandardCharsets.UTF_8);
                    log.info("요청 본문 ({}): {}", request.getRequestURI(), requestBodyString);
                }

                // 응답 본문 로깅
                byte[] responseBody = wrappedResponse.getContentAsByteArray();
                if (responseBody.length > 0) {
                    String responseBodyString = new String(responseBody, StandardCharsets.UTF_8);
                    log.info("응답 본문 ({}): {}", request.getRequestURI(), responseBodyString);
                }

                wrappedResponse.copyBodyToResponse();
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }
}

