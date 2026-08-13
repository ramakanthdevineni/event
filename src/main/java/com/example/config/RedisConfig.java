package com.example.config;

import com.example.service.JdbcSessionStore;
import com.example.service.RedisSessionStore;
import com.example.service.SessionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.data.redis.RedisHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class RedisConfig {

    @Bean
    @ConditionalOnExpression("!'${app.redis-host:}'.isBlank()")
    LettuceConnectionFactory redisConnectionFactory(AppProperties props) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(props.getRedisHost(), props.getRedisPort());
        return new LettuceConnectionFactory(cfg);
    }

    @Bean
    @ConditionalOnExpression("!'${app.redis-host:}'.isBlank()")
    StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    @Bean
    SessionStore sessionStore(AppProperties props,
                              @Qualifier("usersJdbc") JdbcTemplate users,
                              ObjectProvider<StringRedisTemplate> redis) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (props.isRedisEnabled() && template != null) {
            return new RedisSessionStore(template, props);
        }
        return new JdbcSessionStore(users);
    }

    @Bean
    HealthIndicator redisHealthIndicator(AppProperties props, ObjectProvider<LettuceConnectionFactory> factory) {
        LettuceConnectionFactory connectionFactory = factory.getIfAvailable();
        if (!props.isRedisEnabled() || connectionFactory == null) {
            return () -> Health.unknown().withDetail("redis", "disabled").build();
        }
        return new RedisHealthIndicator(connectionFactory);
    }
}
