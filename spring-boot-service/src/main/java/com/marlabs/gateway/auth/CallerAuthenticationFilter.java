package com.marlabs.gateway.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every protected request from its {@code X-Caller-Id} header.
 *
 * <p>A missing, blank, or unrecognised header is rejected with 401 before any
 * controller runs, so no handler ever sees an unauthenticated request. On
 * success the resolved {@link Caller} is placed in a request attribute for
 * controllers to read via {@code @RequestAttribute}.
 */
public class CallerAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute holding the resolved {@link Caller}. */
    public static final String CALLER_ATTRIBUTE = "com.marlabs.gateway.caller";

    public static final String CALLER_HEADER = "X-Caller-Id";

    private static final Logger log = LoggerFactory.getLogger(CallerAuthenticationFilter.class);

    private final CallerRegistry registry;
    private final ObjectMapper objectMapper;

    public CallerAuthenticationFilter(CallerRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String headerValue = request.getHeader(CALLER_HEADER);

        if (headerValue == null || headerValue.isBlank()) {
            reject(request, response, "Missing required " + CALLER_HEADER + " header.", null);
            return;
        }

        Optional<Caller> caller = registry.resolve(headerValue);
        if (caller.isEmpty()) {
            // The supplied id is echoed back so an integrator can see what was
            // sent, but it is never trusted for anything else.
            reject(request, response, "Unknown caller id.", headerValue.trim());
            return;
        }

        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request,
                        HttpServletResponse response,
                        String message,
                        String presentedId) throws IOException {

        log.warn("401 {} {} - {}{}", request.getMethod(), request.getRequestURI(), message,
                presentedId == null ? "" : " (presented: " + presentedId + ")");

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", "Unauthorized");
        body.put("message", message);
        body.put("path", request.getRequestURI());
        if (presentedId != null) {
            body.put("presented_caller_id", presentedId);
        }

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
