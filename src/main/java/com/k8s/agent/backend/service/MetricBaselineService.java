package com.k8s.agent.backend.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메트릭 baseline 계산 서비스
 * 최근 n개 값의 평균을 baseline으로 사용
 */
@Service
public class MetricBaselineService {

    // 메트릭별 히스토리 저장: key = "node:metricName", value = 최근 값들의 리스트
    private final Map<String, Deque<Double>> metricHistory = new ConcurrentHashMap<>();
    
    // EMA (Exponential Moving Average) 저장: key = "node:metricName", value = EMA 값
    private final Map<String, Double> emaValues = new ConcurrentHashMap<>();
    
    // baseline 계산에 사용할 최근 값 개수 (기본값: 10개)
    private static final int BASELINE_WINDOW_SIZE = 10;
    
    // 최대 히스토리 크기 (메모리 관리)
    private static final int MAX_HISTORY_SIZE = 20;
    
    // EMA smoothing factor (alpha) - 0.3은 최근 값에 30% 가중치
    private static final double EMA_ALPHA = 0.3;

    /**
     * 메트릭 값을 히스토리에 추가하고 baseline 반환 (EMA smoothing 적용)
     * @param node 노드 식별자
     * @param metricName 메트릭 이름 (예: "cpu_usage_percent")
     * @param value 현재 메트릭 값
     * @return baseline 값 (EMA smoothing 적용), 히스토리가 부족하면 null
     */
    public Double addMetricAndGetBaseline(String node, String metricName, double value) {
        String key = node + ":" + metricName;
        
        Deque<Double> history = metricHistory.computeIfAbsent(key, k -> new ArrayDeque<>());
        
        // 새 값 추가
        history.addLast(value);
        
        // 최대 크기 제한
        if (history.size() > MAX_HISTORY_SIZE) {
            history.removeFirst();
        }
        
        // EMA 계산 및 업데이트
        Double previousEMA = emaValues.get(key);
        double ema;
        
        if (previousEMA == null) {
            // 첫 번째 값이면 EMA = 현재 값
            ema = value;
        } else {
            // EMA = alpha * current + (1 - alpha) * previous_EMA
            ema = EMA_ALPHA * value + (1 - EMA_ALPHA) * previousEMA;
        }
        
        emaValues.put(key, ema);
        
        // baseline 계산 (최소 BASELINE_WINDOW_SIZE 개의 값이 필요)
        if (history.size() >= BASELINE_WINDOW_SIZE) {
            // EMA 값을 baseline으로 사용
            return ema;
        }
        
        return null; // 히스토리가 부족하여 baseline 계산 불가
    }

    /**
     * 특정 노드와 메트릭의 baseline 조회 (EMA 값 반환)
     * @param node 노드 식별자
     * @param metricName 메트릭 이름
     * @return baseline 값 (EMA), 없으면 null
     */
    public Double getBaseline(String node, String metricName) {
        String key = node + ":" + metricName;
        Deque<Double> history = metricHistory.get(key);
        
        if (history == null || history.size() < BASELINE_WINDOW_SIZE) {
            return null;
        }
        
        // EMA 값 반환
        return emaValues.get(key);
    }

    /**
     * 히스토리 초기화 (테스트용 또는 노드 제거 시)
     */
    public void clearHistory(String node, String metricName) {
        String key = node + ":" + metricName;
        metricHistory.remove(key);
        emaValues.remove(key);
    }

    /**
     * 모든 히스토리 초기화
     */
    public void clearAllHistory() {
        metricHistory.clear();
        emaValues.clear();
    }
}

