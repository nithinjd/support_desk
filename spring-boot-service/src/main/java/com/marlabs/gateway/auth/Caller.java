package com.marlabs.gateway.auth;

/**
 * A verified caller identity.
 *
 * <p>Instances are only ever produced by {@link CallerRegistry} from a trusted
 * {@code X-Caller-Id} lookup. Nothing in a request body, a document, or any
 * other header can construct or alter one; tenant and role are therefore safe
 * to treat as authoritative downstream.
 */
public record Caller(String callerId, String tenant, String role) {
}
