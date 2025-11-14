package com.k8s.agent.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Redis의 Key는 String으로 직렬화
        template.setKeySerializer(new StringRedisSerializer());

        // Redis의 Value는 JSON으로 직렬화 (Jackson)
        // ChatSession 객체가 JSON으로 저장됨
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());

        // Hash의 Key/Value도 동일하게 설정
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        template.afterPropertiesSet();
        return template;
    }
}