package com.example.web;

import com.example.config.ConditionalOnService;
import com.example.config.AppProperties;
import com.example.service.VmsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@ConditionalOnService("core")
public class CoreApiController {
    public static final String SESSION_COOKIE = "SESSIONID";

    private final VmsService vms;
    private final AppProperties props;

    public CoreApiController(VmsService vms, AppProperties props) {
        this.vms = vms;
        this.props = props;
    }

    @PostMapping("/api/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        try {
            Map<String, Object> me = vms.login(str(body, "username"), str(body, "password"));
            String sessionId = (String) me.remove("_sessionId");
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(request, sessionId, false).toString());
            return ResponseEntity.ok(me);
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/api/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        vms.logout(sessionId(request));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(request, "deleted", true).toString());
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @GetMapping({"/api/me", "/api/nav"})
    public ResponseEntity<?> me(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(vms.me(requireUser(request)));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/api/profile")
    public ResponseEntity<?> getProfile(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(vms.profile(requireUser(request)));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/api/profile")
    public ResponseEntity<?> putProfile(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            vms.updateProfile(requireUser(request), str(body, "firstName"), str(body, "lastName"),
                    str(body, "email"), str(body, "password"));
            return ResponseEntity.ok(Map.of("message", "Profile updated successfully."));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/api/change-password")
    public ResponseEntity<?> changePassword(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> result = vms.changePassword(requireUser(request),
                    str(body, "password"), str(body, "confirmPassword"));
            return ResponseEntity.ok(result);
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/api/venues/{id}")
    public ResponseEntity<?> getVenue(HttpServletRequest request, @PathVariable int id) {
        try {
            return ResponseEntity.ok(vms.getVenue(requireUser(request), id));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/api/venues/{id}")
    public ResponseEntity<?> updateVenueItem(HttpServletRequest request, @PathVariable int id,
                                             @RequestBody Map<String, Object> body) {
        try {
            vms.updateVenueItem(requireUser(request), id, str(body, "itemName"), str(body, "status"));
            return ResponseEntity.ok(Map.of("message", "Updated"));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/api/reports")
    public ResponseEntity<?> reports(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(vms.listStatusChangeReports(requireUser(request)));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/api/reports/export")
    public ResponseEntity<byte[]> reportsExport(HttpServletRequest request) {
        try {
            byte[] pdf = vms.reportsPdf(requireUser(request));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"status-change-report.pdf\"")
                    .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).build();
        }
    }

    static String sessionId(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) {
            if (SESSION_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private String requireUser(HttpServletRequest request) {
        String username = vms.resolveSessionUsername(sessionId(request));
        if (username == null) throw new VmsService.ApiException(401, "Unauthorized");
        return username;
    }

    private ResponseCookie sessionCookie(HttpServletRequest request, String value, boolean clear) {
        boolean secure = props.isCookieSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))
                || request.isSecure();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(SESSION_COOKIE, value)
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .sameSite(props.getCookieSameSite() == null || props.getCookieSameSite().isBlank()
                        ? "Lax" : props.getCookieSameSite());
        if (clear) {
            builder.maxAge(0);
        }
        return builder.build();
    }

    static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
