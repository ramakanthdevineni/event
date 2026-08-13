package com.example.security;

import com.example.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private final AppProperties props;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public LoginRateLimitFilter(AppProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (HttpMethod.POST.matches(request.getMethod()) && "/api/login".equals(request.getRequestURI())) {
            prune();
            String key = clientKey(request);
            Window window = windows.computeIfAbsent(key, k -> new Window(System.currentTimeMillis()));
            synchronized (window) {
                long now = System.currentTimeMillis();
                if (now - window.startMs > props.getLoginWindowMs()) {
                    window.startMs = now;
                    window.count.set(0);
                }
                if (window.count.incrementAndGet() > props.getLoginMaxAttempts()) {
                    response.setStatus(429);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Too many login attempts. Please try again later.\"}");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
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
