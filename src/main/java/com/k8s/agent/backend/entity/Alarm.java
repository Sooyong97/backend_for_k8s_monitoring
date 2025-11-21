package com.k8s.agent.backend.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 알람 엔티티
 */
@Entity
@Table(name = "alarms", indexes = {
    @Index(name = "idx_resolved", columnList = "resolved"),
    @Index(name = "idx_severity", columnList = "severity"),
    @Index(name = "idx_type", columnList = "type"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Alarm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlarmType type; // LOG, METRIC

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity; // CRITICAL, WARNING, INFO

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(nullable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private Boolean resolved = false;

    @Column(length = 100)
    private String source; // "AI Agent", "Prometheus", "Loki", etc.

    @Column(columnDefinition = "TEXT")
    private String metadata; // JSON 문자열로 저장

    @Column
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime resolvedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    public enum AlarmType {
        LOG, METRIC;

        @JsonCreator
        public static AlarmType fromString(String value) {
            if (value == null) {
                return null;
            }
            try {
                return AlarmType.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 소문자나 다른 형식도 처리
                for (AlarmType type : AlarmType.values()) {
                    if (type.name().equalsIgnoreCase(value)) {
                        return type;
                    }
                }
                throw new IllegalArgumentException("Unknown AlarmType: " + value);
            }
        }

        @JsonValue
        public String toValue() {
            return this.name();
        }
    }

    public enum Severity {
        CRITICAL, WARNING, INFO;

        @JsonCreator
        public static Severity fromString(String value) {
            if (value == null) {
                return null;
            }
            try {
                return Severity.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 소문자나 다른 형식도 처리
                for (Severity severity : Severity.values()) {
                    if (severity.name().equalsIgnoreCase(value)) {
                        return severity;
                    }
                }
                throw new IllegalArgumentException("Unknown Severity: " + value);
            }
        }

        @JsonValue
        public String toValue() {
            return this.name();
        }
    }
}

