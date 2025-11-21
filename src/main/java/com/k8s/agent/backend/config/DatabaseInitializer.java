package com.k8s.agent.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

/**
 * 데이터베이스 초기화 컴포넌트
 * 애플리케이션 시작 시 alarms 테이블이 없으면 생성
 */
@Component
@Slf4j
public class DatabaseInitializer {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initializeDatabase() {
        try {
            // alarms 테이블 존재 여부 확인
            String checkTableSql = "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'alarms')";
            Boolean tableExists = jdbcTemplate.queryForObject(checkTableSql, Boolean.class);

            if (Boolean.FALSE.equals(tableExists)) {
                log.info("alarms 테이블이 존재하지 않습니다. 생성 중...");
                
                // schema.sql 파일 읽기
                ClassPathResource resource = new ClassPathResource("schema.sql");
                String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                
                // SQL 실행 (세미콜론으로 분리된 여러 문장 처리)
                String[] statements = sql.split(";");
                for (String statement : statements) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                        try {
                            jdbcTemplate.execute(trimmed);
                            log.debug("SQL 실행 완료: {}", trimmed.substring(0, Math.min(50, trimmed.length())));
                        } catch (Exception e) {
                            log.warn("SQL 실행 중 경고 (이미 존재할 수 있음): {}", e.getMessage());
                        }
                    }
                }
                
                log.info("alarms 테이블 생성 완료");
            } else {
                log.info("alarms 테이블이 이미 존재합니다.");
            }
        } catch (Exception e) {
            log.error("데이터베이스 초기화 실패", e);
            // 에러를 던지지 않고 로그만 남김 (애플리케이션 시작은 계속됨)
        }
    }
}

