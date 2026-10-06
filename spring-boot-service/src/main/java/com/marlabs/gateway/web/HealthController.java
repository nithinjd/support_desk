package com.marlabs.gateway.web;

import java.util.List;
import java.util.Map;

import com.marlabs.gateway.auth.Caller;
import com.marlabs.gateway.auth.CallerRegistry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated liveness probe.
 *
 * <p>Deliberately outside the auth filter's URL patterns so an orchestrator can
 * check the gateway without holding a caller id. It lists caller ids only - no
 * tenant or role mapping - so it cannot be used to enumerate the authorisation
 * model.
 */
@RestController
public class HealthController {

    private final CallerRegistry registry;

    public HealthController(CallerRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        List<String> callerIds = registry.all().stream().map(Caller::callerId).toList();
        return Map.of(
                "status", "UP",
                "service", "marlabs-gateway",
                "registered_callers", callerIds.size(),
                "caller_ids", callerIds);
    }
}
