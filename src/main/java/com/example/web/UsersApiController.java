package com.example.web;

import com.example.config.ConditionalOnService;
import com.example.service.VmsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnService("users")
public class UsersApiController {
    private final VmsService vms;

    public UsersApiController(VmsService vms) {
        this.vms = vms;
    }

    @GetMapping("/api/users")
    public ResponseEntity<?> list(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(vms.listUsers(requireUser(request)));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/api/users")
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(vms.createUser(requireUser(request),
                    CoreApiController.str(body, "firstName"),
                    CoreApiController.str(body, "lastName"),
                    CoreApiController.str(body, "email")));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/api/users")
    public ResponseEntity<?> update(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> roles = body.get("roles") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            return ResponseEntity.ok(vms.mutateUser(requireUser(request),
                    CoreApiController.str(body, "action"),
                    CoreApiController.str(body, "username"),
                    CoreApiController.str(body, "firstName"),
                    CoreApiController.str(body, "lastName"),
                    CoreApiController.str(body, "email"),
                    roles));
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
