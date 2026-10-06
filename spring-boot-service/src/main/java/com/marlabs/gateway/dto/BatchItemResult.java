package com.marlabs.gateway.dto;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One manifest entry's outcome.
 *
 * <p>Field names follow the assessment's result contract: {@code document_id},
 * {@code processing_status}, {@code extracted}, {@code field_evidence},
 * {@code policy}, {@code review_required}, {@code issues},
 * {@code duplicate_of} and {@code error}. The remaining fields
 * ({@code sha256}, {@code size_bytes}, {@code injection_suspected},
 * {@code extraction_diagnostics}) are additional context for a reviewer.
 *
 * <p>Three values are invariants rather than computed results, and the factory
 * methods are the only way to build an instance so no call site can set them
 * otherwise:
 *
 * <ul>
 *   <li>{@code reviewRequired} is always {@code true}. Every item this system
 *       touches needs a human to look at it - including the ones it answered
 *       cleanly, because a correct policy quote is still not a claim decision.</li>
 *   <li>{@code decision} is always {@code NO_DECISION}.</li>
 *   <li>{@code paymentInitiated} is always {@code false}.</li>
 * </ul>
 *
 * @param documentId             manifest id
 * @param filename               manifest filename
 * @param processingStatus       {@code COMPLETED} or {@code FAILED}
 * @param reviewRequired         always true
 * @param decision               always {@code NO_DECISION}
 * @param paymentInitiated       always false
 * @param sha256                 hex digest of the uploaded bytes, null if absent
 * @param sizeBytes              uploaded byte count
 * @param duplicateOf            document id of the first item in this batch
 *                               with identical bytes, else null
 * @param injectionSuspected     whether the document tried to steer the system
 * @param extracted              benefit, amount, currency, reference
 * @param fieldEvidence          each supported value mapped to its source quotation
 * @param extractionDiagnostics  everything else the extractor reported
 * @param policy                 the /answer response shape, or null
 * @param issues                 unresolved input facts and policy limitations
 * @param error                  why this item failed; null when COMPLETED
 */
public record BatchItemResult(
        String documentId,
        String filename,
        String processingStatus,
        boolean reviewRequired,
        String decision,
        boolean paymentInitiated,
        String sha256,
        Long sizeBytes,
        String duplicateOf,
        boolean injectionSuspected,
        JsonNode extracted,
        JsonNode fieldEvidence,
        JsonNode extractionDiagnostics,
        JsonNode policy,
        List<Issue> issues,
        ErrorDetail error
) {
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    /** The only decision value ever emitted. */
    public static final String NO_DECISION = "NO_DECISION";

    public static BatchItemResult failed(String documentId, String filename, String sha256,
                                         Long sizeBytes, String duplicateOf,
                                         boolean injectionSuspected,
                                         JsonNode extracted, JsonNode fieldEvidence,
                                         JsonNode extractionDiagnostics,
                                         List<Issue> issues, ErrorDetail error) {
        return new BatchItemResult(documentId, filename, FAILED,
                true, NO_DECISION, false,
                sha256, sizeBytes, duplicateOf, injectionSuspected,
                extracted, fieldEvidence, extractionDiagnostics,
                // policy is null on failure, as the contract requires.
                null,
                issues == null ? List.of() : List.copyOf(issues),
                error);
    }

    public static BatchItemResult completed(String documentId, String filename, String sha256,
                                            Long sizeBytes, String duplicateOf,
                                            boolean injectionSuspected,
                                            JsonNode extracted, JsonNode fieldEvidence,
                                            JsonNode extractionDiagnostics,
                                            JsonNode policy, List<Issue> issues) {
        return new BatchItemResult(documentId, filename, COMPLETED,
                true, NO_DECISION, false,
                sha256, sizeBytes, duplicateOf, injectionSuspected,
                extracted, fieldEvidence, extractionDiagnostics, policy,
                issues == null ? List.of() : List.copyOf(issues),
                // error is null on completion, as the contract requires.
                null);
    }
}
