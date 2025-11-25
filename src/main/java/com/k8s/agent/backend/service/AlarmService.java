package com.k8s.agent.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.k8s.agent.backend.dto.CreateAlarmRequest;
import com.k8s.agent.backend.dto.UpdateAlarmRequest;
import com.k8s.agent.backend.entity.Alarm;
import com.k8s.agent.backend.repository.AlarmRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final ObjectMapper objectMapper;

    /**
     * 알람 목록 조회 (필터링 및 페이지네이션)
     */
    @Transactional(readOnly = true)
    public Page<Alarm> getAlarms(
            Boolean resolved,
            Alarm.Severity severity,
            Alarm.AlarmType type,
            int limit,
            int offset
    ) {
        Pageable pageable = PageRequest.of(offset / limit, limit, Sort.by(Sort.Direction.DESC, "timestamp"));

        // 필터링 조건에 따라 다른 쿼리 실행
        if (resolved != null && severity != null && type != null) {
            return alarmRepository.findByResolvedAndSeverityAndType(resolved, severity, type, pageable);
        } else if (resolved != null && severity != null) {
            return alarmRepository.findByResolvedAndSeverity(resolved, severity, pageable);
        } else if (resolved != null && type != null) {
            return alarmRepository.findByResolvedAndType(resolved, type, pageable);
        } else if (severity != null && type != null) {
            return alarmRepository.findBySeverityAndType(severity, type, pageable);
        } else if (resolved != null) {
            return alarmRepository.findByResolved(resolved, pageable);
        } else if (severity != null) {
            return alarmRepository.findBySeverity(severity, pageable);
        } else if (type != null) {
            return alarmRepository.findByType(type, pageable);
        } else {
            return alarmRepository.findAllByOrderByTimestampDesc(pageable);
        }
    }

    /**
     * 알람 생성
     */
    @Transactional
    public Alarm createAlarm(CreateAlarmRequest request) {
        log.debug("알람 생성 요청: type={}, severity={}, title={}, source={}", 
                request.getType(), request.getSeverity(), request.getTitle(), request.getSource());
        
        // 중복 방지: metadata에서 roomId를 추출하여 최근 5분 내 같은 roomId로 생성된 알람이 있는지 확인
        String roomId = null;
        if (request.getMetadata() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadataMap = null;
                if (request.getMetadata() instanceof String) {
                    metadataMap = objectMapper.readValue((String) request.getMetadata(), 
                            objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                } else if (request.getMetadata() instanceof Map) {
                    metadataMap = (Map<String, Object>) request.getMetadata();
                }
                
                if (metadataMap != null && metadataMap.containsKey("roomId")) {
                    roomId = (String) metadataMap.get("roomId");
                    
                    // 최근 5분 내 같은 roomId로 생성된 알람이 있는지 확인
                    LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
                    List<Alarm> recentAlarms = alarmRepository.findBySourceAndTimestampAfter(
                            request.getSource() != null ? request.getSource() : "AI Agent", 
                            fiveMinutesAgo);
                    
                    for (Alarm recentAlarm : recentAlarms) {
                        if (recentAlarm.getMetadata() != null) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> recentMetadata = objectMapper.readValue(
                                        recentAlarm.getMetadata(), 
                                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                                if (roomId.equals(recentMetadata.get("roomId"))) {
                                    log.warn("중복 알람 생성 방지: roomId={}에 대한 알람이 최근 5분 내에 이미 생성됨. 기존 알람 ID: {}", 
                                            roomId, recentAlarm.getId());
                                    return recentAlarm; // 기존 알람 반환
                                }
                            } catch (Exception e) {
                                // metadata 파싱 실패 시 무시하고 계속 진행
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("중복 체크 중 오류 (무시하고 계속 진행): {}", e.getMessage());
            }
        }
        
        Alarm alarm = new Alarm();
        alarm.setType(request.getType());
        alarm.setSeverity(request.getSeverity());
        alarm.setTitle(request.getTitle());
        alarm.setMessage(request.getMessage());
        alarm.setSource(request.getSource());
        alarm.setTimestamp(LocalDateTime.now());
        alarm.setResolved(false);
        alarm.setNode(request.getNode());
        alarm.setTags(request.getTags());

        // metadata를 JSON 문자열로 변환
        if (request.getMetadata() != null) {
            try {
                // 이미 문자열이면 그대로 사용, 아니면 JSON으로 변환
                if (request.getMetadata() instanceof String) {
                    alarm.setMetadata((String) request.getMetadata());
                } else {
                    alarm.setMetadata(objectMapper.writeValueAsString(request.getMetadata()));
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize metadata to JSON", e);
                alarm.setMetadata("{}");
            }
        }

        Alarm savedAlarm = alarmRepository.save(alarm);
        log.info("알람 생성 완료: id={}, title={}, source={}, roomId={}", 
                savedAlarm.getId(), savedAlarm.getTitle(), savedAlarm.getSource(), roomId);
        return savedAlarm;
    }

    /**
     * 알람 업데이트
     */
    @Transactional
    public Alarm updateAlarm(String id, UpdateAlarmRequest request) {
        Alarm alarm = alarmRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Alarm not found: " + id));

        if (request.getTitle() != null) {
            alarm.setTitle(request.getTitle());
        }
        if (request.getMessage() != null) {
            alarm.setMessage(request.getMessage());
        }
        if (request.getSeverity() != null) {
            alarm.setSeverity(request.getSeverity());
        }
        if (request.getSource() != null) {
            alarm.setSource(request.getSource());
        }
        if (request.getMetadata() != null) {
            try {
                // 이미 문자열이면 그대로 사용, 아니면 JSON으로 변환
                if (request.getMetadata() instanceof String) {
                    alarm.setMetadata((String) request.getMetadata());
                } else {
                    alarm.setMetadata(objectMapper.writeValueAsString(request.getMetadata()));
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize metadata to JSON", e);
            }
        }

        return alarmRepository.save(alarm);
    }

    /**
     * 알람 해결
     */
    @Transactional
    public Alarm resolveAlarm(String id) {
        Alarm alarm = alarmRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Alarm not found: " + id));

        alarm.setResolved(true);
        alarm.setResolvedAt(LocalDateTime.now());

        return alarmRepository.save(alarm);
    }

    /**
     * 알람 삭제
     */
    @Transactional
    public void deleteAlarm(String id) {
        if (!alarmRepository.existsById(id)) {
            throw new RuntimeException("Alarm not found: " + id);
        }
        alarmRepository.deleteById(id);
    }

    /**
     * 알람 조회 (단일)
     */
    @Transactional(readOnly = true)
    public Optional<Alarm> getAlarmById(String id) {
        return alarmRepository.findById(id);
    }
}

