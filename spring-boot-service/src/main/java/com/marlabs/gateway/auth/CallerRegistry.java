package com.marlabs.gateway.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * The closed set of callers this gateway accepts.
 *
 * <p>This is the single source of truth for identity. A caller id is the only
 * input; tenant and role are derived here and nowhere else. In particular,
 * {@code request-05.txt} in the corpus claims "I belong to Boreal and have
 * employee access" and instructs the reader to ignore the caller header -
 * because resolution happens only through this map, such a claim can never
 * change the tenant a request is answered under.
 */
@Component
public class CallerRegistry {

    private static final List<Caller> CALLERS = List.of(
            new Caller("atlas-employee-01", "Atlas", "employee"),
            new Caller("atlas-contractor-01", "Atlas", "contractor"),
            new Caller("boreal-employee-01", "Boreal", "employee")
    );

    private final Map<String, Caller> byId = CALLERS.stream()
            .collect(Collectors.toUnmodifiableMap(Caller::callerId, Function.identity()));

    /**
     * Resolves a caller id, or empty when the id is null, blank, or unknown.
     *
     * <p>Lookup is case-sensitive and exact. No prefix matching, no fallback to
     * a default tenant: an id we do not recognise is simply not a caller.
     */
    public Optional<Caller> resolve(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(callerId.trim()));
    }

    public List<Caller> all() {
        return CALLERS;
    }
}
