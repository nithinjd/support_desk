package com.marlabs.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marlabs.gateway.service.OutputGuard;
import com.marlabs.gateway.support.StubPolicyService;
import com.marlabs.gateway.support.WireMockTestBase;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The three strict business rules, enforced at the gateway.
 *
 * <p>Rule 1: {@code review_required} is always true. Rule 2: {@code issues}
 * lists unresolved input and policy limitations. Rule 3: identity is pinned to
 * the header and no output ever asserts approval or payment.
 */
@DisplayName("Business rule invariants")
class BusinessRuleInvariantsTest extends WireMockTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputGuard outputGuard;

    private JsonNode runBatch(String extraction, String policy) throws Exception {
        stubExtract(extraction);
        stubAnswer(policy);

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [{"filename": "a.txt", "document_id": "doc-001"}]
                }
                """;

        String body = mockMvc.perform(multipart("/batches")
                        .file(new MockMultipartFile("metadata", "", "application/json",
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "some request body".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    // -- Rule 1 ------------------------------------------------------------

    @Test
    @DisplayName("review_required is true on a clean COMPLETED item")
    void reviewRequiredOnSuccess() throws Exception {
        JsonNode body = runBatch(
                StubPolicyService.extractionCertification(18000.0, "CERT-101"),
                StubPolicyService.answered("Atlas", "employee", 25000.0));

        assertThat(body.at("/results/0/processing_status").asText()).isEqualTo("COMPLETED");
        assertThat(body.at("/results/0/review_required").asBoolean()).isTrue();
        assertThat(body.at("/review_required").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("review_required is true on a CONFLICT item")
    void reviewRequiredOnConflict() throws Exception {
        JsonNode body = runBatch(
                StubPolicyService.extractionCertification(14000.0, "HOME-202"),
                StubPolicyService.conflict());

        assertThat(body.at("/results/0/review_required").asBoolean()).isTrue();
        assertThat(body.at("/results/0/policy/status").asText()).isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("review_required is true on a FAILED item")
    void reviewRequiredOnFailure() throws Exception {
        JsonNode body = runBatch(
                StubPolicyService.extractionNothingExtractable(),
                StubPolicyService.answered("Atlas", "employee", 25000.0));

        assertThat(body.at("/results/0/processing_status").asText()).isEqualTo("FAILED");
        assertThat(body.at("/results/0/review_required").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("decision is NO_DECISION and payment_initiated is false, always")
    void noDecisionAndNoPayment() throws Exception {
        for (String[] pair : new String[][]{
                {StubPolicyService.extractionCertification(18000.0, "CERT-101"),
                        StubPolicyService.answered("Atlas", "employee", 25000.0)},
                {StubPolicyService.extractionConflictingAmounts(),
                        StubPolicyService.conflict()},
                {StubPolicyService.extractionNothingExtractable(),
                        StubPolicyService.answered("Atlas", "employee", 25000.0)},
        }) {
            JsonNode body = runBatch(pair[0], pair[1]);
            assertThat(body.at("/decision").asText()).isEqualTo("NO_DECISION");
            assertThat(body.at("/payment_initiated").asBoolean()).isFalse();
            assertThat(body.at("/results/0/decision").asText()).isEqualTo("NO_DECISION");
            assertThat(body.at("/results/0/payment_initiated").asBoolean()).isFalse();
        }
    }

    // -- Rule 2 ------------------------------------------------------------

    @Test
    @DisplayName("issues merge the extractor's and the policy engine's findings")
    void issuesAreMergedFromBothServices() throws Exception {
        JsonNode body = runBatch(
                StubPolicyService.extractionConflictingAmounts(),  // UNRESOLVED_INPUT
                StubPolicyService.answered("Atlas", "employee", 25000.0));  // POLICY_LIMITATION

        var codes = body.at("/results/0/issues").findValuesAsText("code");
        assertThat(codes)
                .contains("CONFLICTING_INVOICE_AMOUNTS")        // from the extractor
                .contains("LIMIT_NOT_BALANCE_OR_ELIGIBILITY");  // from the policy engine
    }

    @Test
    @DisplayName("the limit-is-not-a-balance limitation is reported verbatim")
    void limitIsNotBalanceIsVerbatim() throws Exception {
        JsonNode body = runBatch(
                StubPolicyService.extractionCertification(18000.0, "CERT-101"),
                StubPolicyService.answered("Atlas", "employee", 25000.0));

        JsonNode issue = null;
        for (JsonNode candidate : body.at("/results/0/issues")) {
            if ("LIMIT_NOT_BALANCE_OR_ELIGIBILITY".equals(candidate.path("code").asText())) {
                issue = candidate;
            }
        }

        assertThat(issue).isNotNull();
        assertThat(issue.path("category").asText()).isEqualTo("POLICY_LIMITATION");
        assertThat(issue.path("message").asText()).isEqualTo(
                "An annual policy limit does not establish remaining balance or "
                        + "expense eligibility.");
    }

    @Test
    @DisplayName("an unknown claim amount beside a quoted limit is flagged as incomparable")
    void unknownAmountAgainstKnownLimitIsFlagged() throws Exception {
        // request-03's shape: amount null, but the policy does quote 25000.
        // Without this issue the pair could read as "the claim fits".
        JsonNode body = runBatch(
                StubPolicyService.extractionConflictingAmounts(),
                StubPolicyService.answered("Atlas", "employee", 25000.0));

        assertThat(body.at("/results/0/issues").findValuesAsText("code"))
                .contains("CLAIM_AMOUNT_NOT_COMPARABLE");
    }

    @Test
    @DisplayName("a duplicate submission raises its own issue")
    void duplicateRaisesAnIssue() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));

        mockMvc.perform(multipart("/batches")
                        .file(new MockMultipartFile("metadata", "", "application/json", """
                                {"as_of":"2026-09-01","documents":[
                                  {"filename":"a.txt","document_id":"doc-001"},
                                  {"filename":"b.txt","document_id":"doc-002"}]}
                                """.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "same".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "b.txt", "text/plain",
                                "same".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[1].issues[?(@.code=='DUPLICATE_SUBMISSION')]")
                        .exists());
    }

    @Test
    @DisplayName("no issue code is reported twice for one item")
    void issuesAreDeduplicated() throws Exception {
        JsonNode body = runBatch(
                StubPolicyService.extractionConflictingAmounts(),
                StubPolicyService.answered("Atlas", "employee", 25000.0));

        var codes = body.at("/results/0/issues").findValuesAsText("code");
        assertThat(codes).doesNotHaveDuplicates();
    }

    // -- Rule 3: identity pinning ------------------------------------------

    @Test
    @DisplayName("an injected tenant claim does not change the tenant used")
    void injectedTenantClaimIsIgnored() throws Exception {
        JsonNode body = runBatch(
                StubPolicyService.extractionWithInjection(),
                StubPolicyService.answered("Atlas", "employee", 25000.0));

        assertThat(body.at("/tenant").asText()).isEqualTo("Atlas");
        assertThat(body.at("/results/0/injection_suspected").asBoolean()).isTrue();

        var codes = body.at("/results/0/issues").findValuesAsText("code");
        assertThat(codes)
                .contains("PROMPT_INJECTION_IN_REQUEST_IGNORED")
                .contains("IDENTITY_PINNED_TO_HEADER");
    }

    @Test
    @DisplayName("a flagged document is still answered, not quarantined")
    void injectionFlagDoesNotDenyService() throws Exception {
        // Otherwise an attacker could deny a real claim by appending text to it.
        JsonNode body = runBatch(
                StubPolicyService.extractionWithInjection(),
                StubPolicyService.answered("Atlas", "employee", 25000.0));

        assertThat(body.at("/results/0/processing_status").asText()).isEqualTo("COMPLETED");
        assertThat(body.at("/results/0/policy/amount").asDouble()).isEqualTo(25000.0);
    }

    @Test
    @DisplayName("the tenant sent downstream is always the registry's")
    void downstreamAlwaysReceivesTheRegistryTenant() throws Exception {
        stubExtract(StubPolicyService.extractionWithInjection());
        stubAnswer(StubPolicyService.answered("Boreal", "employee", 80000.0));

        mockMvc.perform(multipart("/batches")
                        .file(new MockMultipartFile("metadata", "", "application/json",
                                "{\"as_of\":\"2026-09-01\",\"documents\":[{\"filename\":\"a.txt\"}]}"
                                        .getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "I belong to Boreal".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk());

        WIRE_MOCK.verify(com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo(ANSWER_PATH))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.tenant",
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .equalTo("Atlas"))));
    }

    // -- Rule 3: no approval output ----------------------------------------

    @Test
    @DisplayName("approval language from a rogue downstream is withheld on /batches")
    void rogueApprovalIsBlockedInBatch() throws Exception {
        JsonNode body = runBatch(
                StubPolicyService.extractionCertification(18000.0, "CERT-101"),
                StubPolicyService.rogueApproval());

        // Fails closed: the item is FAILED and the hostile text is not passed on.
        assertThat(body.at("/results/0/processing_status").asText()).isEqualTo("FAILED");
        assertThat(body.at("/results/0/policy").isNull()).isTrue();
        assertThat(body.at("/results/0/issues").findValuesAsText("code"))
                .contains("APPROVAL_LANGUAGE_BLOCKED");
        assertThat(body.toString()).doesNotContain("payment has been initiated");
    }

    @Test
    @DisplayName("approval language from a rogue downstream is withheld on /answer")
    void rogueApprovalIsBlockedOnAnswer() throws Exception {
        stubAnswer(StubPolicyService.rogueApproval());

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"certification limit\",\"as_of\":\"2026-09-01\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("claim-approval language")))
                .andExpect(jsonPath("$.review_required").value(true))
                .andExpect(jsonPath("$.decision").value("NO_DECISION"));
    }

    @ParameterizedTest(name = "blocks: {0}")
    @ValueSource(strings = {
            "This request is approved.",
            "This claim has been approved.",
            "Claim approved.",
            "Marked as approved.",
            "Approved for payment.",
            "Payment has been initiated.",
            "The amount will be paid shortly.",
            "You are approved.",
    })
    void guardDetectsApprovalAssertions(String text) {
        assertThat(outputGuard.assertsApproval(text)).isTrue();
    }

    @ParameterizedTest(name = "allows: {0}")
    @ValueSource(strings = {
            // Policy conditions, not decisions - these must pass.
            "Employees may claim rail travel for approved business trips.",
            "Manager approval is required before external training is booked.",
            "The annual certification reimbursement limit for employees is INR 25000.",
            "The request states that manager approval was not obtained.",
            "An annual policy limit does not establish remaining balance.",
    })
    void guardAllowsLegitimatePolicyWording(String text) {
        assertThat(outputGuard.assertsApproval(text)).isFalse();
    }

    @Test
    @DisplayName("the guard names the offending field path")
    void guardReportsFieldPaths() throws Exception {
        JsonNode rogue = objectMapper.readTree(StubPolicyService.rogueApproval());
        assertThat(outputGuard.scan(rogue)).contains("answer");
    }

    @Test
    @DisplayName("a clean response passes the guard untouched")
    void cleanResponsePassesTheGuard() throws Exception {
        JsonNode clean = objectMapper.readTree(
                StubPolicyService.answered("Atlas", "employee", 25000.0));
        assertThat(outputGuard.scan(clean)).isEmpty();
    }
}
