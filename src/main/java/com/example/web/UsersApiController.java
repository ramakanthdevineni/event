package com.example.web;

import com.example.config.ConditionalOnService;
import com.example.service.VmsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
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
    public ResponseEntity<?> list(HttpServletRequest request,
                                  @RequestParam(value = "page", defaultValue = "1") int page,
                                  @RequestParam(value = "pageSize", defaultValue = "100") int pageSize) {
        try {
            return ResponseEntity.ok(vms.listUsers(requireUser(request), page, pageSize));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/api/users/export")
    public ResponseEntity<byte[]> export(HttpServletRequest request) {
        try {
            byte[] csv = vms.exportUsersCsv(requireUser(request));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.csv\"")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(csv);
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).build();
        }
    }

    @PostMapping(value = "/api/users/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importCsv(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Please choose a CSV file."));
            }
            return ResponseEntity.ok(vms.importUsersCsv(requireUser(request), file.getBytes()));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("message", "Unable to import users right now."));
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

    @PutMapping("/api/users/bulk-roles")
    public ResponseEntity<?> bulkRoles(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> usernames = body.get("usernames") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            @SuppressWarnings("unchecked")
            List<String> roles = body.get("roles") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            return ResponseEntity.ok(vms.bulkUpdateUserRoles(requireUser(request), usernames, roles));
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
