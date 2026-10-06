package com.marlabs.gateway.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.marlabs.gateway.client.PolicyServiceException;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Uniform JSON error bodies across every failure mode. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(
            BadRequestException exc, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, exc.getMessage(), null, request);
    }

    /** Bean-validation failures on {@code @Valid} bodies. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(
            MethodArgumentNotValidException exc, HttpServletRequest request) {

        List<String> details = exc.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        return build(HttpStatus.BAD_REQUEST, "Request validation failed", details, request);
    }

    @ExceptionHandler({MissingServletRequestPartException.class,
                       MissingServletRequestParameterException.class,
                       MultipartException.class})
    public ResponseEntity<Map<String, Object>> multipart(
            Exception exc, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, exc.getMessage(), null, request);
    }

    /**
     * Unparseable or absent request body.
     *
     * <p>Without this, malformed client JSON falls through to the catch-all and
     * reports 500 - blaming the server for the caller's syntax error.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(
            HttpMessageNotReadableException exc, HttpServletRequest request) {

        Throwable cause = exc.getCause();
        String detail = cause instanceof JsonProcessingException json
                ? json.getOriginalMessage()
                : exc.getMessage();

        return build(HttpStatus.BAD_REQUEST,
                "Request body is missing or not valid JSON: " + detail, null, request);
    }

    /**
     * Downstream failure: 504 when the Python service never answered, 502 when
     * it answered unusably. Never 500 - the gateway itself is working.
     */
    @ExceptionHandler(PolicyServiceException.class)
    public ResponseEntity<Map<String, Object>> downstream(
            PolicyServiceException exc, HttpServletRequest request) {

        log.error("Downstream failure on {}: {}", request.getRequestURI(), exc.getMessage());

        Map<String, Object> body = baseBody(exc.gatewayStatus(), exc.getMessage(), request);
        if (exc.downstreamStatus() != null) {
            body.put("downstream_status", exc.downstreamStatus().value());
        }
        return ResponseEntity.status(exc.gatewayStatus()).body(body);
    }

    /**
     * An output invariant was violated, so nothing is returned.
     *
     * <p>502 rather than 500: the gateway worked correctly by refusing to pass
     * on what the downstream service produced.
     */
    @ExceptionHandler(ResponseWithheldException.class)
    public ResponseEntity<Map<String, Object>> withheld(
            ResponseWithheldException exc, HttpServletRequest request) {

        log.error("Output withheld on {}: {}", request.getRequestURI(), exc.getMessage());

        Map<String, Object> body = baseBody(HttpStatus.BAD_GATEWAY, exc.getMessage(), request);
        body.put("review_required", true);
        body.put("decision", "NO_DECISION");
        body.put("payment_initiated", false);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(
            Exception exc, HttpServletRequest request) {

        log.error("Unhandled exception on {}", request.getRequestURI(), exc);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                exc.getClass().getSimpleName() + ": " + exc.getMessage(), null, request);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message,
                                                      List<String> details,
                                                      HttpServletRequest request) {
        Map<String, Object> body = baseBody(status, message, request);
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> baseBody(HttpStatus status, String message,
                                         HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return body;
    }
}
