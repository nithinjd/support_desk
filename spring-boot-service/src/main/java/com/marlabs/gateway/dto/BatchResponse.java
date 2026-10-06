package com.marlabs.gateway.dto;

import java.util.List;

/**
 * Response of {@code POST /batches}, always HTTP 200 once the manifest is valid.
 *
 * <p>The assessment fixes three parts of this shape: {@code batch_id},
 * {@code summary} with {@code total}/{@code completed}/{@code failed}, and
 * {@code results} in manifest order. The caller and invariant fields are
 * additional context.
 *
 * @param batchId          echoed or generated batch id
 * @param callerId         the authenticated caller
 * @param tenant           tenant the batch was answered under, from the registry
 * @param role             role the batch was answered under, from the registry
 * @param asOf             evaluation date applied to every item
 * @param summary          counts of what happened
 * @param reviewRequired   always true
 * @param decision         always {@code NO_DECISION}
 * @param paymentInitiated always false
 * @param decisionNote     plain-English statement of what this service does not do
 * @param results          one entry per manifest document, in manifest order
 */
public record BatchResponse(
        String batchId,
        String callerId,
        String tenant,
        String role,
        String asOf,
        Summary summary,
        boolean reviewRequired,
        String decision,
        boolean paymentInitiated,
        String decisionNote,
        List<BatchItemResult> results
) {
    /**
     * How many items were submitted and how they turned out.
     *
     * @param total     manifest size
     * @param completed items that produced a result
     * @param failed    items that could not be processed
     */
    public record Summary(int total, int completed, int failed) {
    }

    private static final String NOTE =
            "This service returns policy information only. It does not approve, deny, or "
            + "pay claims, and no payment is ever initiated. Every item requires human review.";

    /**
     * Builds a response with the batch-level invariants fixed.
     *
     * <p>{@code reviewRequired} is true for the batch as a whole as well as for
     * every item, so a client reading only the envelope cannot conclude the
     * batch was dispositive.
     */
    public static BatchResponse of(String batchId, String callerId, String tenant, String role,
                                   String asOf, int total, int completed, int failed,
                                   List<BatchItemResult> results) {
        return new BatchResponse(batchId, callerId, tenant, role, asOf,
                new Summary(total, completed, failed),
                true, BatchItemResult.NO_DECISION, false, NOTE, results);
    }
}
