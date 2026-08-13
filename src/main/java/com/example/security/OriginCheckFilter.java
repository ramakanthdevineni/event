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
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Blocks cross-site mutating API calls when Origin/Referer does not match the request host
 * or an explicitly allowed CORS origin.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OriginCheckFilter extends OncePerRequestFilter {
    private final AppProperties props;

    public OriginCheckFilter(AppProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        boolean mutating = HttpMethod.POST.matches(method)
                || HttpMethod.PUT.matches(method)
                || HttpMethod.PATCH.matches(method)
                || HttpMethod.DELETE.matches(method);
        if (mutating && request.getRequestURI().startsWith("/api/")) {
            String origin = request.getHeader("Origin");
            String referer = request.getHeader("Referer");
            boolean hasOrigin = origin != null && !origin.isBlank();
            boolean hasReferer = referer != null && !referer.isBlank();
            if (!hasOrigin && !hasReferer) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Missing Origin or Referer\"}");
                return;
            }
            String candidate = hasOrigin ? origin : referer;
            if (!isAllowed(candidate, request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Forbidden origin\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(String candidate, HttpServletRequest request) {
        String host = hostOf(candidate);
        if (host == null) {
            return false;
        }
        String requestHost = request.getServerName();
        if (host.equalsIgnoreCase(requestHost) || "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
            return true;
        }
        // Behind nginx the Host header is the public host; also allow configured CORS origins.
        String publicHost = request.getHeader("X-Forwarded-Host");
        if (publicHost != null && !publicHost.isBlank()) {
            String fwd = publicHost.split(",")[0].trim();
            if (fwd.contains(":")) {
                fwd = fwd.substring(0, fwd.indexOf(':'));
            }
            if (host.equalsIgnoreCase(fwd)) {
                return true;
            }
        }
        Set<String> allowed = props.corsOriginHosts();
        return allowed.stream().anyMatch(a -> a.equalsIgnoreCase(host));
    }

    private static String hostOf(String urlOrOrigin) {
        try {
            String value = urlOrOrigin.trim();
            if (!value.contains("://")) {
                value = "http://" + value;
            }
            URI uri = URI.create(value);
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            return null;
        }
    }
}
