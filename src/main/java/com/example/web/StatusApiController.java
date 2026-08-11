package com.example.web;

import com.example.config.ConditionalOnService;
import com.example.service.VmsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnService("status")
public class StatusApiController {
    private final VmsService vms;

    public StatusApiController(VmsService vms) {
        this.vms = vms;
    }

    @GetMapping("/api/status")
    public ResponseEntity<?> status(HttpServletRequest request,
                                    @RequestParam(value = "optionId", required = false) Integer optionId) {
        try {
            return ResponseEntity.ok(vms.status(requireUser(request), optionId));
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/api/status/export")
    public ResponseEntity<byte[]> export(HttpServletRequest request) {
        try {
            byte[] pdf = vms.statusPdf(requireUser(request));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"status-report.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (VmsService.ApiException ex) {
            return ResponseEntity.status(ex.status).build();
        }
    }

    private String requireUser(HttpServletRequest request) {
        String username = vms.resolveSessionUsername(CoreApiController.sessionId(request));
        if (username == null) throw new VmsService.ApiException(401, "Unauthorized");
        return username;
    }
}
