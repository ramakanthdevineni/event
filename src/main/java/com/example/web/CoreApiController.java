package com.example.web;

import com.example.config.ConditionalOnService;
import com.example.service.VmsService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@ConditionalOnService("core")
public class CoreApiController {
    public static final String SESSION_COOKIE = "SESSIONID";

    private final VmsService vms;

    public CoreApiController(VmsService vms) {
        this.vms = vms;
    }

    @PostMapping("/api/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        try {
            Map<String, Object> me = vms.login(str(body, "username"), str(body, "password"));
            String sessionId = (String) me.remove("_sessionId");
            Cookie cookie = new Cookie(SESSION_COOKIE, sessionId);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            response.addCookie(cookie);
            return ResponseEntity.ok(me);
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/api/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        vms.logout(sessionId(request));
        Cookie cookie = new Cookie(SESSION_COOKIE, "deleted");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
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

    static String sessionId(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (SESSION_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private String requireUser(HttpServletRequest request) {
        String username = vms.resolveSessionUsername(sessionId(request));
        if (username == null) throw new VmsService.ApiException(401, "Unauthorized");
        return username;
    }

    static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
