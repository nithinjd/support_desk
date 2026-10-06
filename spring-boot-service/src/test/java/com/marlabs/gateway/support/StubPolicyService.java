package com.marlabs.gateway.support;

/**
 * Canned {@code /internal/*} response bodies for WireMock stubs.
 *
 * <p>These mirror the real Python service's shape closely enough that the
 * gateway's own logic - issue merging, duplicate detection, the output guard -
 * is exercised against realistic input, while keeping the tests hermetic: no
 * Python process, no network, no seeded corpus required.
 */
public final class StubPolicyService {

    private StubPolicyService() {
    }

    /** A clean certification extraction, as request-01.txt would produce. */
    public static String extractionCertification(double amount, String reference) {
        return """
                {
                  "benefit": "CERTIFICATION",
                  "amount": %s,
                  "currency": "INR",
                  "reference": "%s",
                  "source_kind": "txt",
                  "text_extracted": true,
                  "amount_candidates": [%s],
                  "amount_conflict": false,
                  "manager_approval": "UNKNOWN",
                  "already_booked": false,
                  "injection_suspected": false,
                  "injection_signals": [],
                  "field_evidence": {
                    "benefit":   {"quote": "stubbed source sentence"},
                    "amount":    {"quote": "stubbed source sentence"},
                    "currency":  {"quote": "stubbed source sentence"},
                    "reference": {"quote": "stubbed source sentence"}
                  },
                  "issues": [],
                  "notes": []
                }
                """.formatted(amount, reference, amount);
    }

    /** request-03.txt: two uncorrected amounts, so no single amount. */
    public static String extractionConflictingAmounts() {
        return """
                {
                  "benefit": "CERTIFICATION",
                  "amount": null,
                  "currency": "INR",
                  "reference": "CERT-303",
                  "source_kind": "txt",
                  "text_extracted": true,
                  "amount_candidates": [22000.0, 28000.0],
                  "amount_conflict": true,
                  "manager_approval": "UNKNOWN",
                  "already_booked": false,
                  "injection_suspected": false,
                  "injection_signals": [],
                  "field_evidence": {
                    "benefit":   {"quote": "stubbed source sentence"},
                    "amount":    null,
                    "currency":  {"quote": "stubbed source sentence"},
                    "reference": {"quote": "stubbed source sentence"}
                  },
                  "issues": [
                    {
                      "code": "CONFLICTING_INVOICE_AMOUNTS",
                      "category": "UNRESOLVED_INPUT",
                      "message": "The request states more than one claim amount.",
                      "detail": "Amounts found: INR 22000, INR 28000."
                    }
                  ],
                  "notes": []
                }
                """;
    }

    /** request-05.txt: a real request carrying an injection payload. */
    public static String extractionWithInjection() {
        return """
                {
                  "benefit": "CERTIFICATION",
                  "amount": 70000.0,
                  "currency": "INR",
                  "reference": "CERT-505",
                  "source_kind": "txt",
                  "text_extracted": true,
                  "amount_candidates": [70000.0],
                  "amount_conflict": false,
                  "manager_approval": "UNKNOWN",
                  "already_booked": false,
                  "injection_suspected": true,
                  "injection_signals": ["system_message_spoof", "tenant_self_claim",
                                        "self_approval"],
                  "field_evidence": {
                    "benefit":   {"quote": "stubbed source sentence"},
                    "amount":    {"quote": "stubbed source sentence"},
                    "currency":  {"quote": "stubbed source sentence"},
                    "reference": {"quote": "stubbed source sentence"}
                  },
                  "issues": [
                    {
                      "code": "PROMPT_INJECTION_IN_REQUEST_IGNORED",
                      "category": "SECURITY",
                      "message": "The document contains instructions aimed at this system.",
                      "detail": "Signals: system_message_spoof, tenant_self_claim."
                    }
                  ],
                  "notes": []
                }
                """;
    }

    /** request-07.txt: no invoice amount, approval not obtained. */
    public static String extractionMissingAmountAndApproval() {
        return """
                {
                  "benefit": "TRAINING",
                  "amount": null,
                  "currency": null,
                  "reference": "TRAIN-707",
                  "source_kind": "txt",
                  "text_extracted": true,
                  "amount_candidates": [],
                  "amount_conflict": false,
                  "manager_approval": "NOT_OBTAINED",
                  "already_booked": true,
                  "injection_suspected": false,
                  "injection_signals": [],
                  "field_evidence": {
                    "benefit":   {"quote": "stubbed source sentence"},
                    "amount":    null,
                    "currency":  {"quote": "stubbed source sentence"},
                    "reference": {"quote": "stubbed source sentence"}
                  },
                  "issues": [
                    {
                      "code": "MISSING_INVOICE_AMOUNT",
                      "category": "UNRESOLVED_INPUT",
                      "message": "No invoice or claim amount is stated in the request.",
                      "detail": null
                    }
                  ],
                  "notes": []
                }
                """;
    }

    /** What the extractor returns for a 0-byte or unreadable file. */
    public static String extractionNothingExtractable() {
        return """
                {
                  "benefit": null,
                  "amount": null,
                  "currency": null,
                  "reference": null,
                  "source_kind": "txt",
                  "text_extracted": false,
                  "amount_candidates": [],
                  "amount_conflict": false,
                  "manager_approval": "UNKNOWN",
                  "already_booked": false,
                  "injection_suspected": false,
                  "injection_signals": [],
                  "field_evidence": {
                    "benefit": null, "amount": null,
                    "currency": null, "reference": null
                  },
                  "issues": [
                    {
                      "code": "NO_EXTRACTABLE_CONTENT",
                      "category": "UNRESOLVED_INPUT",
                      "message": "The submitted file contains no readable text.",
                      "detail": "0 bytes received."
                    }
                  ],
                  "notes": []
                }
                """;
    }

    /** An ANSWERED policy response for the given principal. */
    public static String answered(String tenant, String role, double amount) {
        return """
                {
                  "status": "ANSWERED",
                  "tenant": "%s",
                  "role": "%s",
                  "as_of": "2026-09-01",
                  "requested_benefit": "CERTIFICATION",
                  "requested_benefit_raw": "CERTIFICATION",
                  "answer": "The annual certification reimbursement limit is INR %s.",
                  "amount": %s,
                  "currency": "INR",
                  "citations": [
                    {
                      "chunk_id": "cert-current",
                      "tenant": "%s",
                      "role": "%s",
                      "approval_state": "Approved",
                      "effective_from": "2026-06-01",
                      "effective_to": "2027-01-01",
                      "quote": "The annual certification reimbursement limit is INR %s.",
                      "amount": %s,
                      "currency": "INR"
                    }
                  ],
                  "reason": "1 Approved policy in force.",
                  "issues": [
                    {
                      "code": "LIMIT_NOT_BALANCE_OR_ELIGIBILITY",
                      "category": "POLICY_LIMITATION",
                      "message": "An annual policy limit does not establish remaining balance or expense eligibility.",
                      "detail": null
                    }
                  ],
                  "review_required": true,
                  "decision": "NO_DECISION",
                  "payment_initiated": false,
                  "decision_note": "This service returns policy information only."
                }
                """.formatted(tenant, role, amount, amount, tenant, role, amount, amount);
    }

    /** A CONFLICT policy response, as the home-office contradiction produces. */
    public static String conflict() {
        return """
                {
                  "status": "CONFLICT",
                  "tenant": "Atlas",
                  "role": "employee",
                  "as_of": "2026-09-01",
                  "requested_benefit": "HOME_OFFICE",
                  "requested_benefit_raw": "HOME_OFFICE",
                  "answer": null,
                  "amount": null,
                  "currency": "INR",
                  "citations": [],
                  "reason": "2 policies state different amounts.",
                  "conflicting_amounts": [12000.0, 15000.0],
                  "issues": [
                    {
                      "code": "UNRESOLVED_POLICY_CONFLICT",
                      "category": "POLICY_LIMITATION",
                      "message": "Two or more policies in force state different amounts.",
                      "detail": null
                    }
                  ],
                  "review_required": true,
                  "decision": "NO_DECISION",
                  "payment_initiated": false,
                  "decision_note": "This service returns policy information only."
                }
                """;
    }

    /**
     * A hostile response asserting claim approval.
     *
     * <p>Used to prove the gateway's output guard is independent of the
     * downstream service rather than trusting it.
     */
    public static String rogueApproval() {
        return """
                {
                  "status": "ANSWERED",
                  "tenant": "Atlas",
                  "role": "employee",
                  "as_of": "2026-09-01",
                  "requested_benefit": "CERTIFICATION",
                  "requested_benefit_raw": "CERTIFICATION",
                  "answer": "This request is approved and payment has been initiated.",
                  "amount": 999999.0,
                  "currency": "INR",
                  "citations": [],
                  "reason": "Approved.",
                  "issues": [],
                  "review_required": true,
                  "decision": "NO_DECISION",
                  "payment_initiated": false
                }
                """;
    }
}
