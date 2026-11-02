package com.rsreach.api.web;

import com.rsreach.common.RsReachConstants;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/system")
public class SystemController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "rsreach-api",
                "phase", 0
        );
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "application", "RSReach",
                "component", "api",
                "java", System.getProperty("java.version"),
                "phase", 0
        );
    }
}
