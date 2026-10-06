package com.marlabs.gateway.client;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * A call to the Python service did not produce a usable answer.
 *
 * <p>{@link #gatewayStatus()} is what the gateway should report to its own
 * client: 504 when the downstream never answered in time, 502 when it answered
 * with something unusable. Both say "the fault is behind this gateway", which a
 * 500 would not.
 */
public class PolicyServiceException extends RuntimeException {

    private final HttpStatus gatewayStatus;
    private final HttpStatusCode downstreamStatus;

    public PolicyServiceException(String message, HttpStatus gatewayStatus,
                                  HttpStatusCode downstreamStatus, Throwable cause) {
        super(message, cause);
        this.gatewayStatus = gatewayStatus;
        this.downstreamStatus = downstreamStatus;
    }

    public static PolicyServiceException timeout(String endpoint, Throwable cause) {
        return new PolicyServiceException(
                "Policy service did not respond within the timeout for " + endpoint
                        + " (no retry is attempted): " + cause.getMessage(),
                HttpStatus.GATEWAY_TIMEOUT, null, cause);
    }

    /**
     * The downstream answered, but with something we cannot use.
     *
     * <p>{@code status} is null when the failure was not an HTTP status at all
     * - malformed JSON, an empty body, an unreadable content type. That case
     * must not be treated as exceptional here: this constructor runs on the
     * error path, so dereferencing a null status would replace a clean 502
     * with a 500 NullPointerException and hide the real cause.
     */
    public static PolicyServiceException badResponse(String endpoint, HttpStatusCode status,
                                                     String body) {
        String what;
        if (status != null) {
            what = "Policy service returned " + status.value() + " for " + endpoint;
        } else {
            what = "Policy service returned an unusable response for " + endpoint;
        }

        if (body != null && !body.isBlank()) {
            what = what + ": " + body;
        }

        return new PolicyServiceException(what, HttpStatus.BAD_GATEWAY, status, null);
    }

    public HttpStatus gatewayStatus() {
        return gatewayStatus;
    }

    public HttpStatusCode downstreamStatus() {
        return downstreamStatus;
    }
}
