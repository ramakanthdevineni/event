package com.example.web;

import com.example.config.ConditionalOnService;
import com.example.service.VmsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@ConditionalOnService("admin")
public class AdminApiController {
    private final VmsService vms;

    public AdminApiController(VmsService vms) {
        this.vms = vms;
    }

    @GetMapping("/api/admin")
    public ResponseEntity<?> get(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(vms.adminSnapshot(requireUser(request)));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/api/admin")
    public ResponseEntity<?> post(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return mutate(request, body);
    }

    @PutMapping("/api/admin")
    public ResponseEntity<?> put(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return mutate(request, body);
    }

    @DeleteMapping("/api/admin")
    public ResponseEntity<?> delete(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return mutate(request, body);
    }

    private ResponseEntity<?> mutate(HttpServletRequest request, Map<String, Object> body) {
        try {
            return ResponseEntity.ok(vms.adminAction(requireUser(request), body));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    private String requireUser(HttpServletRequest request) {
        String username = vms.resolveSessionUsername(CoreApiController.sessionId(request));
        if (username == null) throw new VmsService.ApiException(401, "Unauthorized");
        return username;
    }
}
