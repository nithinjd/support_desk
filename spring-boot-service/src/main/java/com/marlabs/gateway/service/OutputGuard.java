package com.marlabs.gateway.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * Final barrier on what the gateway is allowed to say.
 *
 * <p>This gateway reports policy information. It never adjudicates a claim and
 * never moves money, so output that reads as an approval or a payment
 * instruction is a defect regardless of how it got there - an injected
 * instruction in a request, a hostile passage in the corpus, or a future code
 * change.
 *
 * <p>The Python service screens its own output, so this is deliberately a
 * second, independent check at the trust boundary: the gateway does not assume
 * the downstream service is uncompromised. Passthrough JSON is exactly where an
 * unvetted string could otherwise reach a client unexamined.
 *
 * <p>Patterns are narrow on purpose. The corpus legitimately contains
 * "approved" in {@code "Employees may claim rail travel for approved business
 * trips"} and {@code "Manager approval is required before external training is
 * booked"}. Those are policy conditions, not decisions, and must pass.
 */
@Component
public class OutputGuard {

    /** The only decision value this gateway ever emits. */
    public static final String DECISION_NONE = "NO_DECISION";

    private static final List<Pattern> APPROVAL_ASSERTIONS = List.of(
            Pattern.compile("\\bthis\\s+(?:request|claim)\\s+(?:is|has\\s+been|was)\\s+approved\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:claim|request)\\s+approved\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmark(?:ed|ing)?\\s+(?:as\\s+)?approved\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bapproved\\s+for\\s+(?:payment|reimbursement|payout)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpayment\\s+(?:has\\s+been\\s+)?(?:initiated|released|made|processed|sent)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwill\\s+be\\s+(?:paid|reimbursed|disbursed)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\byou\\s+(?:are|have\\s+been)\\s+approved\\b", Pattern.CASE_INSENSITIVE)
    );

    /** Fields whose own text describes the no-decision policy; exempt. */
    private static final List<String> EXEMPT_FIELDS = List.of("decision_note");

    /**
     * True if this text asserts a decision about the claim in front of us.
     *
     * <p>THE IMPORTANT SUBTLETY: the word "approved" is not itself a problem.
     * Our policy corpus legitimately says "rail travel for approved business
     * trips" and "Manager approval is required before external training is
     * booked". Those are policy CONDITIONS and we must keep quoting them.
     *
     * <p>What we must never say is "this request is approved" or "payment has
     * been initiated". So the patterns above match whole phrases, not the
     * keyword. A keyword check would block our own travel and training
     * policies - breaking real answers while adding no safety at all.
     */
    public boolean assertsApproval(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (Pattern pattern : APPROVAL_ASSERTIONS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks every string anywhere in a response and names the offending fields.
     *
     * @return field paths such as {@code ["answer", "citations[0].text"]};
     *         empty when the payload is clean
     */
    public List<String> scan(JsonNode node) {
        List<String> violations = new ArrayList<>();
        walk(node, "", violations);
        return violations;
    }

    /**
     * Visits every value in the JSON tree, recording the path as it goes.
     *
     * <p>Recursive because a response is nested: the strings we care about can
     * sit at the top level, inside the citations array, or inside an issue.
     * Rather than guess which fields matter, we check all of them.
     */
    private void walk(JsonNode node, String path, List<String> violations) {
        if (node == null || node.isNull()) {
            return;
        }

        // An object: visit each field, extending the path with its name.
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();

                // decision_note's own text explains the no-decision policy,
                // so scanning it would flag our own disclaimer.
                if (EXEMPT_FIELDS.contains(name)) {
                    continue;
                }

                String childPath;
                if (path.isEmpty()) {
                    childPath = name;
                } else {
                    childPath = path + "." + name;
                }

                walk(field.getValue(), childPath, violations);
            }
            return;
        }

        // An array: visit each element, extending the path with its index.
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", violations);
            }
            return;
        }

        // An actual string: this is where the check happens.
        if (node.isTextual()) {
            if (assertsApproval(node.asText())) {
                if (path.isEmpty()) {
                    violations.add("<root>");
                } else {
                    violations.add(path);
                }
            }
        }

        // Numbers, booleans and nulls cannot contain approval wording.
    }
}
