# 메트릭 API 명세서 (멀티노드 지원)

## 개요

모든 메트릭 API는 멀티노드 환경을 지원하며, 각 응답 항목에 노드 정보(`instance`, `node`)가 포함됩니다.

---

## 1. CPU 사용률 조회

### 엔드포인트
```
GET /api/metrics/node/cpu
```

### 응답 형식
```json
{
  "status": "success",
  "data": [
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "45.23"
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "52.15"
    }
  ]
}
```

### 필드 설명
- `instance` (string): Prometheus instance 식별자 (예: "10.0.2.131:9100")
- `node` (string): 노드 식별자
  - Prometheus 메트릭에 `node` 라벨이 있으면 그 값을 사용 (예: "node1", "node2")
  - `node` 라벨이 없으면 `instance`에서 포트를 제거한 IP/호스트명 사용 (예: "10.0.2.131")
- `metric` (object): Prometheus 원본 메트릭 라벨
- `timestamp` (string): Unix 타임스탬프 (초)
- `value` (string): CPU 사용률 (%)

---

## 2. CPU 사용률 범위 조회 (그래프용)

### 엔드포인트
```
GET /api/metrics/node/cpu/range?minutes=5
```

### Query Parameters
- `minutes` (optional): 조회할 시간 범위 (기본값: 5)

### 응답 형식
```json
{
  "status": "success",
  "data": [
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "__name__": "..."
      },
      "values": [
        ["1705747800", "45.23"],
        ["1705747810", "46.12"],
        ["1705747820", "44.89"]
      ]
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "__name__": "..."
      },
      "values": [
        ["1705747800", "52.15"],
        ["1705747810", "53.22"],
        ["1705747820", "51.98"]
      ]
    }
  ]
}
```

### 필드 설명
- `instance` (string): Prometheus instance 식별자
- `node` (string): 노드 호스트명 또는 IP
- `metric` (object): Prometheus 원본 메트릭 라벨
- `values` (array): `[timestamp, value]` 형식의 배열

---

## 3. Memory 사용률 조회

### 엔드포인트
```
GET /api/metrics/node/memory
```

### 응답 형식
```json
{
  "status": "success",
  "data": [
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "68.45"
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "72.33"
    }
  ]
}
```

### 필드 설명
- `instance` (string): Prometheus instance 식별자
- `node` (string): 노드 호스트명 또는 IP
- `metric` (object): Prometheus 원본 메트릭 라벨
- `timestamp` (string): Unix 타임스탬프 (초)
- `value` (string): Memory 사용률 (%)

---

## 4. Memory 사용률 범위 조회 (그래프용)

### 엔드포인트
```
GET /api/metrics/node/memory/range?minutes=5
```

### Query Parameters
- `minutes` (optional): 조회할 시간 범위 (기본값: 5)

### 응답 형식
```json
{
  "status": "success",
  "data": [
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "__name__": "..."
      },
      "values": [
        ["1705747800", "68.45"],
        ["1705747810", "69.12"],
        ["1705747820", "67.89"]
      ]
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "__name__": "..."
      },
      "values": [
        ["1705747800", "72.33"],
        ["1705747810", "73.45"],
        ["1705747820", "71.22"]
      ]
    }
  ]
}
```

---

## 5. Network 트래픽 조회

### 엔드포인트
```
GET /api/metrics/node/network
```

### 응답 형식
```json
{
  "status": "success",
  "data": [
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "direction": "received",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "1234567.89"
    },
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "direction": "transmitted",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "987654.32"
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "direction": "received",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "2345678.90"
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "direction": "transmitted",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "1234567.89"
    }
  ]
}
```

### 필드 설명
- `instance` (string): Prometheus instance 식별자
- `node` (string): 노드 호스트명 또는 IP
- `metric.direction` (string): "received" 또는 "transmitted"
- `timestamp` (string): Unix 타임스탬프 (초)
- `value` (string): 네트워크 트래픽 (bytes/sec)

**참고:** 각 노드마다 `received`와 `transmitted` 두 개의 항목이 반환됩니다.

---

## 6. Network 트래픽 범위 조회 (그래프용)

### 엔드포인트
```
GET /api/metrics/node/network/range?minutes=5
```

### Query Parameters
- `minutes` (optional): 조회할 시간 범위 (기본값: 5)

### 응답 형식
```json
{
  "status": "success",
  "data": [
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "direction": "received",
        "__name__": "..."
      },
      "values": [
        ["1705747800", "1234567.89"],
        ["1705747810", "1234568.12"],
        ["1705747820", "1234567.45"]
      ]
    },
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "direction": "transmitted",
        "__name__": "..."
      },
      "values": [
        ["1705747800", "987654.32"],
        ["1705747810", "987655.11"],
        ["1705747820", "987653.98"]
      ]
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "direction": "received",
        "__name__": "..."
      },
      "values": [
        ["1705747800", "2345678.90"],
        ["1705747810", "2345679.45"],
        ["1705747820", "2345678.12"]
      ]
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "direction": "transmitted",
        "__name__": "..."
      },
      "values": [
        ["1705747800", "1234567.89"],
        ["1705747810", "1234568.22"],
        ["1705747820", "1234567.55"]
      ]
    }
  ]
}
```

---

## 7. Disk 사용률 조회

### 엔드포인트
```
GET /api/metrics/node/disk
```

### 응답 형식
```json
{
  "status": "success",
  "data": [
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": {
        "instance": "10.0.2.131:9100",
        "mountpoint": "/",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "35.67"
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": {
        "instance": "10.0.2.132:9100",
        "mountpoint": "/",
        "__name__": "..."
      },
      "timestamp": "1705747800",
      "value": "42.11"
    }
  ]
}
```

---

## 프론트엔드 구현 가이드

### 1. 멀티노드 데이터 처리

모든 메트릭 API는 배열을 반환하며, 각 항목은 하나의 노드를 나타냅니다.

```typescript
interface MetricItem {
  instance: string;      // "10.0.2.131:9100"
  node: string;          // "10.0.2.131"
  metric: {
    instance: string;
    [key: string]: string;
  };
  timestamp?: string;    // Instant query에만 존재
  value?: string;        // Instant query에만 존재
  values?: Array<[string, string]>;  // Range query에만 존재
}

interface MetricResponse {
  status: "success" | "error";
  data: MetricItem[];
  message?: string;      // 에러 시에만 존재
}
```

### 2. 노드별로 그룹화

```typescript
// 노드별로 데이터 그룹화
const groupByNode = (data: MetricItem[]) => {
  const grouped: Record<string, MetricItem[]> = {};
  data.forEach(item => {
    if (!grouped[item.node]) {
      grouped[item.node] = [];
    }
    grouped[item.node].push(item);
  });
  return grouped;
};

// 사용 예시
const response = await fetch('/api/metrics/node/cpu');
const json: MetricResponse = await response.json();
const byNode = groupByNode(json.data);

// 각 노드별로 처리
Object.keys(byNode).forEach(node => {
  console.log(`Node ${node}:`, byNode[node]);
});
```

### 3. Network 메트릭 처리 (direction별)

```typescript
// Network 메트릭은 각 노드마다 received와 transmitted 두 개가 있음
const processNetworkMetrics = (data: MetricItem[]) => {
  const byNode: Record<string, {
    received?: MetricItem;
    transmitted?: MetricItem;
  }> = {};
  
  data.forEach(item => {
    const node = item.node;
    if (!byNode[node]) {
      byNode[node] = {};
    }
    
    const direction = item.metric.direction;
    if (direction === 'received') {
      byNode[node].received = item;
    } else if (direction === 'transmitted') {
      byNode[node].transmitted = item;
    }
  });
  
  return byNode;
};
```

### 4. 그래프 표시 (Range Query)

```typescript
// Range query 데이터를 그래프 라이브러리에 맞게 변환
const prepareChartData = (data: MetricItem[]) => {
  return data.map(item => ({
    label: item.node,  // 또는 item.instance
    data: item.values?.map(([timestamp, value]) => ({
      x: parseInt(timestamp) * 1000,  // JavaScript Date는 밀리초
      y: parseFloat(value)
    })) || []
  }));
};
```

### 5. 노드 선택 UI

```typescript
// 노드 목록 추출
const getUniqueNodes = (data: MetricItem[]): string[] => {
  const nodes = new Set<string>();
  data.forEach(item => nodes.add(item.node));
  return Array.from(nodes).sort();
};

// 사용 예시
const nodes = getUniqueNodes(json.data);
// 드롭다운 또는 탭으로 노드 선택 UI 구성
```

### 6. 에러 처리

```typescript
const fetchMetrics = async (endpoint: string) => {
  try {
    const response = await fetch(endpoint);
    const json: MetricResponse = await response.json();
    
    if (json.status === 'error') {
      console.error('메트릭 조회 실패:', json.message);
      return [];
    }
    
    if (!json.data || json.data.length === 0) {
      console.warn('메트릭 데이터가 비어있습니다.');
      return [];
    }
    
    return json.data;
  } catch (error) {
    console.error('네트워크 에러:', error);
    return [];
  }
};
```

---

## 변경 사항 요약

### 이전 형식 (단일 노드 가정)
```json
{
  "status": "success",
  "data": [
    {
      "metric": { "instance": "10.0.2.131:9100" },
      "value": "45.23"
    }
  ]
}
```

### 새로운 형식 (멀티노드 지원)
```json
{
  "status": "success",
  "data": [
    {
      "instance": "10.0.2.131:9100",
      "node": "10.0.2.131",
      "metric": { "instance": "10.0.2.131:9100" },
      "value": "45.23"
    },
    {
      "instance": "10.0.2.132:9100",
      "node": "10.0.2.132",
      "metric": { "instance": "10.0.2.132:9100" },
      "value": "52.15"
    }
  ]
}
```

### 주요 변경점
1. ✅ 각 항목에 `instance` 필드 추가 (최상위 레벨)
2. ✅ 각 항목에 `node` 필드 추가 (포트 제거된 호스트명/IP)
3. ✅ `metric` 객체는 기존과 동일하게 유지 (하위 호환성)
4. ✅ 배열 형식 유지 (멀티노드 지원)

---

## 마이그레이션 가이드

### 기존 코드 (단일 노드 가정)
```typescript
const value = json.data[0].value;  // 첫 번째 항목만 사용
```

### 새로운 코드 (멀티노드 지원)
```typescript
// 모든 노드 처리
json.data.forEach(item => {
  console.log(`Node ${item.node}: ${item.value}`);
});

// 특정 노드만 필터링
const node131 = json.data.find(item => item.node === '10.0.2.131');
```

---

## 참고사항

1. **`instance` vs `node`**: 
   - `instance`: Prometheus의 전체 instance 식별자 (포트 포함)
   - `node`: 사용자 친화적인 노드 식별자 (포트 제거)

2. **Network 메트릭**: 
   - 각 노드마다 `received`와 `transmitted` 두 개의 항목이 반환됩니다.
   - `metric.direction` 필드로 구분할 수 있습니다.

3. **빈 배열**: 
   - Prometheus 서버가 다운되었거나 메트릭이 없는 경우 빈 배열(`[]`)이 반환됩니다.
   - 프론트엔드에서 빈 배열을 처리해야 합니다.

4. **에러 응답**: 
   ```json
   {
     "status": "error",
     "message": "에러 메시지"
   }
   ```

