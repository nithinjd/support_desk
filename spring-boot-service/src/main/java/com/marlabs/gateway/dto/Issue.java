package com.marlabs.gateway.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Something a human reviewer needs told before acting on an item.
 *
 * <p>Categories mirror the Python service's vocabulary exactly, because issues
 * from both services are merged into one list per batch item:
 *
 * <ul>
 *   <li>{@code UNRESOLVED_INPUT} - the document is incomplete or
 *       self-contradictory; someone must go back to the claimant.</li>
 *   <li>{@code POLICY_LIMITATION} - the corpus cannot settle the question, or
 *       answers only a narrower one than was asked.</li>
 *   <li>{@code SECURITY} - the input or a retrieved passage tried to steer the
 *       system. Recorded, never acted on.</li>
 * </ul>
 *
 * @param code     stable machine-readable identifier
 * @param category one of the three above
 * @param message  one sentence for a human
 * @param detail   optional specifics, e.g. the conflicting amounts found
 */
public record Issue(String code, String category, String message, String detail) {

    public static final String UNRESOLVED_INPUT = "UNRESOLVED_INPUT";
    public static final String POLICY_LIMITATION = "POLICY_LIMITATION";
    public static final String SECURITY = "SECURITY";

    public static Issue unresolvedInput(String code, String message, String detail) {
        return new Issue(code, UNRESOLVED_INPUT, message, detail);
    }

    public static Issue policyLimitation(String code, String message, String detail) {
        return new Issue(code, POLICY_LIMITATION, message, detail);
    }

    public static Issue security(String code, String message, String detail) {
        return new Issue(code, SECURITY, message, detail);
    }

    /** Rebuilds an issue emitted by the Python service. */
    public static Issue fromJson(JsonNode node) {
        return new Issue(
                node.path("code").asText(null),
                node.path("category").asText(null),
                node.path("message").asText(null),
                node.path("detail").isNull() ? null : node.path("detail").asText(null));
    }
}
