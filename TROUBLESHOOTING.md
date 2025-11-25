# 이상탐지 시스템 트러블슈팅 가이드

이 문서는 이상탐지 시스템 구현 및 운영 중 발생한 주요 이슈와 해결 방법을 정리한 것입니다.

## 목차
1. [컴파일 오류](#1-컴파일-오류)
2. [AI Agent 통신 오류](#2-ai-agent-통신-오류)
3. [Prometheus 통신 오류](#3-prometheus-통신-오류)
4. [Loki 통신 오류](#4-loki-통신-오류)
5. [메트릭 데이터 누락 문제](#5-메트릭-데이터-누락-문제)
6. [중복 알림 전송 문제](#6-중복-알림-전송-문제)
7. [애플리케이션 종료 시 오류](#7-애플리케이션-종료-시-오류)

---

## 1. 컴파일 오류

### 문제: `continue` 문을 루프 외부에서 사용

**증상:**
```
error: continue outside of loop
```

**원인:**
Java의 `forEach` 람다 표현식 내부에서 `continue` 문을 사용할 수 없습니다. `continue`는 전통적인 for 루프에서만 사용 가능합니다.

**발생 위치:**
- `RealtimeStreamController.java`의 `detectAnomalies()` 메서드
- `forEach` 루프 내부에서 조건을 만족하지 않을 때 다음 반복으로 건너뛰려고 시도

**해결 방법:**
`continue` 대신 `return`을 사용하여 람다 표현식의 현재 반복을 종료합니다.

```java
// ❌ 잘못된 코드
response.getData().getResult().forEach(result -> {
    if (condition) {
        continue; // 컴파일 오류!
    }
    // ...
});

// ✅ 올바른 코드
response.getData().getResult().forEach(result -> {
    if (condition) {
        return; // 람다 표현식 종료
    }
    // ...
});
```

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/controller/RealtimeStreamController.java`

---

## 2. AI Agent 통신 오류

### 2.1 타임아웃 오류

**증상:**
```
이상탐지 알림 전송 실패: node=10.0.2.131, metric=memory_usage_percent, 
error=Did not observe any item or terminal signal within 5000ms in 'flatMap' 
(and no fallback has been configured)
```

**원인:**
- AI Agent 서버(`http://10.0.2.134:8000/analyze`)의 응답 시간이 5초를 초과
- 네트워크 지연 또는 서버 부하로 인한 응답 지연
- 일시적인 연결 문제

**해결 방법:**

1. **타임아웃 시간 증가**: 5초 → 30초
2. **재시도 로직 추가**: 최대 2번 재시도 (총 3번 시도)
3. **재시도 조건 필터링**: 타임아웃 및 연결 오류만 재시도

```java
webClient.post()
    .uri("/analyze")
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(payload)
    .retrieve()
    .bodyToMono(String.class)
    .timeout(Duration.ofSeconds(30)) // 타임아웃 30초로 증가
    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)) // 최대 2번 재시도
        .filter(throwable -> {
            String errorMsg = throwable.getMessage();
            return errorMsg != null && (
                errorMsg.contains("timeout") ||
                errorMsg.contains("Timeout") ||
                errorMsg.contains("prematurely closed") ||
                throwable instanceof java.util.concurrent.TimeoutException
            );
        })
    )
    .subscribe(...);
```

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/service/AnomalyNotificationService.java`

---

### 2.2 422 UNPROCESSABLE_ENTITY 오류

**증상:**
```
POST /chatm 요청 시 422 오류
{
  "detail": [
    {
      "type": "string_type",
      "loc": ["body", "initial_context"],
      "msg": "Input should be a valid string",
      "input": null
    }
  ]
}
```

**원인:**
- Python FastAPI 서버가 `initial_context` 필드에 `null` 값을 허용하지 않음
- FastAPI의 Pydantic 모델이 `str` 타입을 요구하지만 `null`이 전송됨

**해결 방법:**
`null` 대신 빈 문자열(`""`)을 전송합니다.

```java
// ❌ 잘못된 코드
ChatRequest request = new ChatRequest();
request.setInitialContext(null); // FastAPI에서 거부

// ✅ 올바른 코드
ChatRequest request = new ChatRequest();
request.setInitialContext(""); // 빈 문자열로 전송
```

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/client/AiAgentClient.java`
- `getManualChatResponse()` 메서드

---

## 3. Prometheus 통신 오류

### 문제: Prometheus 쿼리 타임아웃

**증상:**
```
java.util.concurrent.TimeoutException: Did not observe any item or terminal signal within 10000ms in 'flatMap' (and no fallback has been configured)
```

**발생 위치:**
- `PrometheusClient.query()` 메서드
- `MetricsController`의 CPU, Memory, Disk, System 메트릭 조회
- `RealtimeStreamController`의 이상탐지 메트릭 수집

**원인:**
- Prometheus 서버 응답 시간이 10초를 초과
- 복잡한 PromQL 쿼리나 서버 부하로 인한 지연
- 네트워크 지연

**해결 방법:**

1. **타임아웃 시간 증가**: 10초 → 30초
2. **재시도 로직 추가**: 최대 2번 재시도 (총 3번 시도)
3. **재시도 조건 필터링**: 타임아웃 오류만 재시도

```java
return webClient.get()
    .uri(uri)
    .accept(MediaType.APPLICATION_JSON)
    .retrieve()
    .bodyToMono(PrometheusResponse.class)
    .timeout(Duration.ofSeconds(30)) // 10초 → 30초로 증가
    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)) // 최대 2번 재시도
        .filter(throwable -> {
            String errorMsg = throwable.getMessage();
            return errorMsg != null && (
                errorMsg.contains("timeout") ||
                errorMsg.contains("Timeout") ||
                throwable instanceof java.util.concurrent.TimeoutException
            );
        })
    )
    .doOnError(error -> log.error("Prometheus query 실패: {}", promql, error));
```

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/client/PrometheusClient.java`
- `query()` 메서드 및 `queryRange()` 메서드

---

## 4. Loki 통신 오류

### 문제: Loki 서버 연결 거부 및 긴 에러 로그

**증상:**
```
Connection refused: /10.0.2.189:32001
```
- Loki 서버에 연결할 수 없을 때 전체 스택 트레이스 출력 (400+ 줄)
- 연결 거부 오류가 ERROR 레벨로 로깅되어 로그가 지나치게 상세함

**원인:**
- Loki 서버가 다운되어 있거나 네트워크 문제
- 일시적인 연결 문제
- 타임아웃 설정이 짧음 (10초)

**해결 방법:**

1. **타임아웃 시간 증가**: 10초 → 30초
2. **재시도 로직 추가**: 타임아웃 오류만 재시도 (연결 거부는 재시도하지 않음)
3. **에러 로깅 간소화**: 연결 거부는 WARN 레벨로 간단히 로깅

```java
return webClient.get()
    .uri(uri)
    .accept(MediaType.APPLICATION_JSON)
    .retrieve()
    .bodyToMono(LokiResponse.class)
    .timeout(Duration.ofSeconds(30)) // 10초 → 30초로 증가
    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)) // 타임아웃만 재시도
        .filter(throwable -> {
            String errorMsg = throwable.getMessage();
            return errorMsg != null && (
                errorMsg.contains("timeout") ||
                errorMsg.contains("Timeout") ||
                throwable instanceof java.util.concurrent.TimeoutException
            );
        })
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
```

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/client/LokiClient.java`
- `query()` 메서드 및 `queryRange()` 메서드
- `src/main/java/com/k8s/agent/backend/controller/RealtimeStreamController.java`
- `detectErrorLogs()` 메서드

---

## 5. 메트릭 데이터 누락 문제

### 3.1 job, namespace 라벨 누락

**증상:**
- AI 서버로 전송되는 메트릭에 `job`과 `namespace` 라벨이 포함되지 않음
- Prometheus 쿼리 결과에는 존재하지만, 이상탐지 알림에는 누락

**원인:**
- PromQL의 `avg by (instance)` 또는 `sum by (instance)` 사용 시 다른 라벨이 자동으로 제거됨
- 집계 함수 사용 시 `by` 절에 명시된 라벨만 유지됨

**해결 방법:**
모든 필요한 라벨을 `by` 절에 명시합니다.

```java
// ❌ 잘못된 코드
"avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))"

// ✅ 올바른 코드
"avg by (instance, job, namespace, container, endpoint, pod, service) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))"
```

**적용된 쿼리:**
- CPU: `avg by (instance, job, namespace, container, endpoint, pod, service)`
- Memory: 나눗셈 연산은 라벨 자동 유지 (수정 불필요)
- Disk I/O: `sum by (instance, job, namespace, container, endpoint, pod, service)`
- Network: `sum by (instance, job, namespace, container, endpoint, pod, service)`
- 시스템 에러 지표: `sum by (instance, job, namespace, container, endpoint, pod, service)`

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/controller/RealtimeStreamController.java`
- `detectAnomalies()` 메서드 내 모든 PromQL 쿼리

---

### 3.2 빈 메트릭 값 전송

**증상:**
- AI 서버로 전송되는 메트릭의 `value` 필드가 비어있거나 누락
- 원본 Prometheus 메트릭 데이터가 제대로 전달되지 않음

**원인:**
- `additionalData`를 `putAll`로 병합하면서 기존 필드가 덮어쓰여짐
- 메트릭 데이터 구조가 올바르게 보존되지 않음

**해결 방법:**
원본 Prometheus 메트릭 데이터(`metric` + `value`)를 그대로 사용하고, 이상탐지 정보만 추가합니다.

```java
// ❌ 잘못된 코드
Map<String, Object> payload = new HashMap<>();
payload.put("node", node);
payload.put("metric_name", metricName);
payload.put("value", value); // 개별 필드로 추출
// ... 기타 필드

// ✅ 올바른 코드
Map<String, Object> payload = new HashMap<>(originalMetric); // 원본 메트릭 데이터 복사
payload.put("metric_name", metricName); // 이상탐지 정보만 추가
payload.put("anomaly_detected", true);
payload.put("severity", severity.name());
payload.put("message", message);
payload.put("detection_timestamp", currentTime);
```

**전송되는 JSON 구조:**
```json
{
  "metric": {
    "instance": "10.0.2.131:9100",
    "job": "node-exporter",
    "namespace": "monitoring",
    "container": "node-exporter",
    "endpoint": "http-metrics",
    "pod": "prometheus-prometheus-node-exporter-gfqfv",
    "service": "prometheus-prometheus-node-exporter"
  },
  "value": ["1763940574.735", "95.23"],
  "metric_name": "cpu_usage_percent",
  "anomaly_detected": true,
  "severity": "CRITICAL",
  "message": "CPU 사용률이 90%를 초과했습니다.",
  "detection_timestamp": 1763940574
}
```

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/service/AnomalyNotificationService.java`
- `sendNotification()` 메서드

---

## 6. 중복 알림 전송 문제

### 문제: 같은 이상이 계속 전송됨

**증상:**
- 같은 메트릭(예: `disk_read_iops`)의 이상이 탐지될 때마다 계속 전송됨
- 10분 쿨다운이 제대로 작동하지 않음

**원인:**
- 같은 메트릭이지만 severity가 변경되면 새로운 키로 인식되어 즉시 전송됨
- severity가 변경되지 않아도 쿨다운이 제대로 적용되지 않음

**해결 방법:**

1. **중복 방지 키 구조 개선**
   - 메트릭: `"node:metricName:severity"` 형식
   - 로그: `"node:logType:severity:messageHash"` 형식

2. **Severity 변경 감지**
   - 같은 메트릭의 severity가 변경되면 즉시 전송 (쿨다운 무시)
   - severity가 변경되지 않으면 10분 쿨다운 적용

3. **마지막 severity 추적**
   - 별도의 `ConcurrentHashMap`으로 마지막 severity 저장
   - severity 변경 여부를 확인하여 전송 여부 결정

```java
// 마지막 severity 추적
private final Map<String, String> lastSeverityMap = new ConcurrentHashMap<>();

// 중복 방지 로직
String baseKey = String.format("%s:%s", node, itemName);
String lastSeverity = lastSeverityMap.get(baseKey);
boolean severityChanged = lastSeverity != null && !lastSeverity.equals(severity.name());

Long lastSentTime = lastNotificationTime.get(notificationKey);
if (lastSentTime != null) {
    long timeSinceLastNotification = currentTime - lastSentTime;
    
    // severity가 변경되지 않았고, 10분이 지나지 않았으면 전송하지 않음
    if (!severityChanged && timeSinceLastNotification < NOTIFICATION_COOLDOWN_SECONDS) {
        return; // 중복 알림 방지
    }
}

// 마지막 severity 저장
lastSeverityMap.put(baseKey, severity.name());
```

**동작 방식:**
1. 첫 번째 이상 탐지: 즉시 전송
2. 같은 severity로 계속 이상 탐지: 10분 쿨다운 적용
3. severity 변경 (예: WARNING → CRITICAL): 즉시 전송 (쿨다운 무시)

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/service/AnomalyNotificationService.java`
- `sendNotification()` 메서드

---

## 7. 애플리케이션 종료 시 오류

### 문제: 종료 중 알림 전송 시도로 인한 오류

**증상:**
- 애플리케이션 종료 시 긴 에러 로그 출력 (400+ 줄)
- 불필요한 알림 전송 시도

**원인:**
- `@Scheduled` 작업이 종료 중에도 계속 실행됨
- 종료 중 WebClient 호출로 인한 예외 발생

**해결 방법:**

1. **종료 상태 플래그 추가**
   - `AtomicBoolean isShuttingDown`으로 종료 상태 추적
   - `@PreDestroy`로 종료 시점 감지

2. **종료 중 알림 전송 중단**
   - 종료 중에는 새로운 알림 전송 시도하지 않음

3. **에러 로깅 간소화**
   - 전체 스택 트레이스 대신 간단한 에러 메시지만 출력
   - 연결 종료 오류는 별도로 간단히 로깅

```java
@PreDestroy
public void onShutdown() {
    log.info("애플리케이션 종료 중 - 이상탐지 알림 전송 중단");
    isShuttingDown.set(true);
}

public void sendNotification(...) {
    if (isShuttingDown.get()) {
        log.debug("애플리케이션 종료 중 - 알림 전송 건너옴: node={}, item={}, type={}", 
                node, itemName, dataType);
        return;
    }
    // ... 알림 전송 로직
}
```

**참고 파일:**
- `src/main/java/com/k8s/agent/backend/service/AnomalyNotificationService.java`
- `@PreDestroy` 메서드 및 `isShuttingDown` 플래그

---

## 추가 참고 사항

### 로그 레벨 조정
- 일시적인 네트워크 오류는 `log.error` → `log.warn`으로 변경
- 연결 종료 오류는 별도로 간단히 로깅

### 성능 최적화
- `ConcurrentHashMap` 사용으로 스레드 안전성 보장
- 메트릭 수집과 이상탐지를 분리하여 프론트엔드 구독 여부와 무관하게 실행

### 모니터링 권장 사항
- AI Agent 서버 응답 시간 모니터링
- 이상탐지 알림 전송 성공/실패율 추적
- 중복 알림 방지 로직의 효과 확인

---

## 관련 파일 목록

### 핵심 파일
- `src/main/java/com/k8s/agent/backend/service/AnomalyNotificationService.java`
- `src/main/java/com/k8s/agent/backend/controller/RealtimeStreamController.java`
- `src/main/java/com/k8s/agent/backend/service/AnomalyDetectionService.java`
- `src/main/java/com/k8s/agent/backend/service/MetricBaselineService.java`
- `src/main/java/com/k8s/agent/backend/client/AiAgentClient.java`

### 설정 파일
- `src/main/resources/application.properties`

---

**작성일:** 2025년 1월
**최종 업데이트:** 이상탐지 시스템 구현 완료 후

