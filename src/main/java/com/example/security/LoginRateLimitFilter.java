package com.example.security;

import com.example.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private final AppProperties props;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public LoginRateLimitFilter(AppProperties props, ObjectProvider<StringRedisTemplate> redis) {
        this.props = props;
        this.redis = redis.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (HttpMethod.POST.matches(request.getMethod()) && "/api/login".equals(request.getRequestURI())) {
            String key = clientKey(request);
            boolean allowed = redis != null ? allowRedis(key) : allowLocal(key);
            if (!allowed) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Too many login attempts. Please try again later.\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowRedis(String clientKey) {
        try {
            String redisKey = "vms:login:" + clientKey;
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, Duration.ofMillis(Math.max(1000L, props.getLoginWindowMs())));
            }
            return count == null || count <= props.getLoginMaxAttempts();
        } catch (Exception ex) {
            return allowLocal(clientKey);
        }
    }

    private boolean allowLocal(String clientKey) {
        prune();
        Window window = windows.computeIfAbsent(clientKey, k -> new Window(System.currentTimeMillis()));
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.startMs > props.getLoginWindowMs()) {
                window.startMs = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= props.getLoginMaxAttempts();
        }
    }

    private void prune() {
        if (windows.size() < 2_000) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            if (now - e.getValue().startMs > props.getLoginWindowMs() * 2) {
                it.remove();
            }
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private static final class Window {
        long startMs;
        final AtomicInteger count = new AtomicInteger();

        Window(long startMs) {
            this.startMs = startMs;
        }
    }
}
