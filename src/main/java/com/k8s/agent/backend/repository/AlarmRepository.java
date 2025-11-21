package com.k8s.agent.backend.repository;

import com.k8s.agent.backend.entity.Alarm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlarmRepository extends JpaRepository<Alarm, String> {

    // 해결 여부로 필터링
    Page<Alarm> findByResolved(Boolean resolved, Pageable pageable);

    // 심각도로 필터링
    Page<Alarm> findBySeverity(Alarm.Severity severity, Pageable pageable);

    // 타입으로 필터링
    Page<Alarm> findByType(Alarm.AlarmType type, Pageable pageable);

    // 해결 여부 + 심각도
    Page<Alarm> findByResolvedAndSeverity(Boolean resolved, Alarm.Severity severity, Pageable pageable);

    // 해결 여부 + 타입
    Page<Alarm> findByResolvedAndType(Boolean resolved, Alarm.AlarmType type, Pageable pageable);

    // 심각도 + 타입
    Page<Alarm> findBySeverityAndType(Alarm.Severity severity, Alarm.AlarmType type, Pageable pageable);

    // 해결 여부 + 심각도 + 타입
    Page<Alarm> findByResolvedAndSeverityAndType(
            Boolean resolved, 
            Alarm.Severity severity, 
            Alarm.AlarmType type, 
            Pageable pageable
    );

    // 최신순 정렬 (기본)
    Page<Alarm> findAllByOrderByTimestampDesc(Pageable pageable);
    
    // 중복 방지: source와 timestamp로 최근 알람 조회
    List<Alarm> findBySourceAndTimestampAfter(String source, LocalDateTime timestamp);
}

