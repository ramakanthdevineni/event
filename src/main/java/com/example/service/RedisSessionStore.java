package com.example.service;

import com.example.config.AppProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.Set;

public class RedisSessionStore implements SessionStore {
    private static final String SESS = "vms:sess:";
    private static final String USER = "vms:usess:";

    private final StringRedisTemplate redis;
    private final AppProperties props;

    public RedisSessionStore(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public void create(String sessionId, String username, long lastActivityMs) {
        Duration ttl = ttl();
        redis.opsForValue().set(SESS + sessionId, username + "|" + lastActivityMs, ttl);
        redis.opsForZSet().add(USER + username, sessionId, lastActivityMs);
        redis.expire(USER + username, ttl.plus(ttl));
    }

    @Override
    public Session find(String sessionId) {
        String raw = redis.opsForValue().get(SESS + sessionId);
        if (raw == null || raw.isBlank()) return null;
        int sep = raw.lastIndexOf('|');
        if (sep <= 0) return null;
        String username = raw.substring(0, sep);
        long last;
        try {
            last = Long.parseLong(raw.substring(sep + 1));
        } catch (NumberFormatException ex) {
            return null;
        }
        return new Session(sessionId, username, last);
    }

    @Override
    public void touch(String sessionId, long lastActivityMs) {
        Session existing = find(sessionId);
        if (existing == null) return;
        Duration ttl = ttl();
        redis.opsForValue().set(SESS + sessionId, existing.username() + "|" + lastActivityMs, ttl);
        redis.opsForZSet().add(USER + existing.username(), sessionId, lastActivityMs);
        redis.expire(USER + existing.username(), ttl.plus(ttl));
    }

    @Override
    public void delete(String sessionId) {
        Session existing = find(sessionId);
        redis.delete(SESS + sessionId);
        if (existing != null) {
            redis.opsForZSet().remove(USER + existing.username(), sessionId);
        }
    }

    @Override
    public void deleteByUsername(String username) {
        Set<String> ids = redis.opsForZSet().range(USER + username, 0, -1);
        if (ids != null) {
            for (String id : ids) {
                redis.delete(SESS + id);
            }
        }
        redis.delete(USER + username);
    }

    @Override
    public void pruneOldest(String username, int keep) {
        Long size = redis.opsForZSet().zCard(USER + username);
        if (size == null || size <= keep) return;
        Set<ZSetOperations.TypedTuple<String>> oldest = redis.opsForZSet()
                .rangeWithScores(USER + username, 0, size - keep - 1);
        if (oldest == null) return;
        for (ZSetOperations.TypedTuple<String> t : oldest) {
            String id = t.getValue();
            if (id != null) {
                redis.delete(SESS + id);
                redis.opsForZSet().remove(USER + username, id);
            }
        }
    }

    @Override
    public int purgeExpired(long cutoffMs) {
        return 0;
    }

    private Duration ttl() {
        return Duration.ofMillis(Math.max(60_000L, props.getSessionTimeoutMs()));
    }
}
