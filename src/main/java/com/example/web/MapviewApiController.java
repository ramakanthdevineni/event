package com.example.web;

import com.example.config.ConditionalOnService;
import com.example.service.VmsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnService("mapview")
public class MapviewApiController {
    private final VmsService vms;

    public MapviewApiController(VmsService vms) {
        this.vms = vms;
    }

    @GetMapping("/api/mapview")
    public ResponseEntity<?> map(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(vms.mapview(requireUser(request)));
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
