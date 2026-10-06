package com.marlabs.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.marlabs.gateway.support.StubPolicyService;
import com.marlabs.gateway.support.WireMockTestBase;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * Behaviour when the Python service accepts the connection then stalls.
 *
 * <p>This is the harder case than a dead port: the TCP handshake succeeds, so
 * the failure arrives as a *read* timeout, and a read timeout that trips while
 * the response body is being consumed surfaces as {@code RestClientException}
 * rather than {@code ResourceAccessException}. Mapping that to 504 instead of
 * 500 is the behaviour under test - it was a real bug before these tests.
 *
 * <p>The timeout is shortened to 1s here so the suite stays fast. That the
 * production value is 5s is asserted separately in {@code PolicyServiceDownTest}
 * against the real {@code application.yml}.
 */
@TestPropertySource(properties = "policy-service.timeout=1s")
@DisplayName("Python service stalls")
class PolicyServiceTimeoutTest extends WireMockTestBase {

    private static final String QUESTION =
            "{\"question\":\"certification limit\",\"as_of\":\"2026-09-01\"}";

    @Test
    @DisplayName("a stalled response becomes 504, not 500")
    void stalledAnswerBecomesGatewayTimeout() throws Exception {
        stubDelayed(ANSWER_PATH, 5_000);   // well beyond the 1s budget

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value(504));
    }

    @Test
    @DisplayName("the timeout fires near the configured budget, not much later")
    void timeoutFiresWithinTheBudget() throws Exception {
        stubDelayed(ANSWER_PATH, 10_000);

        long start = System.nanoTime();
        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isGatewayTimeout());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // Must give up on its own budget rather than waiting for the stub.
        assertThat(elapsedMillis)
                .as("elapsed %d ms should be near the 1s budget", elapsedMillis)
                .isLessThan(5_000L);
    }

    @Test
    @DisplayName("exactly one attempt is made - zero retries")
    void exactlyOneDownstreamAttempt() throws Exception {
        stubDelayed(ANSWER_PATH, 5_000);

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isGatewayTimeout());

        // The requirement is 0 retries. A retry would show as a second request.
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(ANSWER_PATH)));
    }

    @Test
    @DisplayName("a stalled extraction fails only its own batch item")
    void stalledExtractionFailsOneItem() throws Exception {
        stubDelayed(EXTRACT_PATH, 5_000);

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [{"filename": "a.txt", "document_id": "doc-001"}]
                }
                """;

        mockMvc.perform(multipart("/batches")
                        .file(new MockMultipartFile("metadata", "", "application/json",
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "certification INR 100".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.failed").value(1))
                .andExpect(jsonPath("$.results[0].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].error.code").value("EXTRACTION_FAILED"));
    }

    @Test
    @DisplayName("one stalled item does not stop the others")
    void oneStalledItemDoesNotPoisonTheBatch() throws Exception {
        // Extraction works; the policy call stalls. Every item fails at the
        // same stage, but the batch still returns a full set of results.
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));
        stubDelayed(ANSWER_PATH, 3_000);

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [
                    {"filename": "a.txt", "document_id": "doc-001"},
                    {"filename": "b.txt", "document_id": "doc-002"}
                  ]
                }
                """;

        mockMvc.perform(multipart("/batches")
                        .file(new MockMultipartFile("metadata", "", "application/json",
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "aaa".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "b.txt", "text/plain",
                                "bbb".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(2))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].issues[?(@.code=='POLICY_LOOKUP_UNAVAILABLE')]")
                        .exists());
    }

    // -- non-2xx downstream responses --------------------------------------

    @Test
    @DisplayName("a downstream 500 becomes 502, not 500")
    void downstreamErrorBecomesBadGateway() throws Exception {
        stubStatus(ANSWER_PATH, 500, "{\"detail\":\"corpus not loaded\"}");

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.downstream_status").value(500))
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("corpus not loaded")));
    }

    @Test
    @DisplayName("a downstream 503 becomes 502 and is not retried")
    void downstreamUnavailableIsNotRetried() throws Exception {
        stubStatus(ANSWER_PATH, 503, "{\"detail\":\"policy corpus not found\"}");

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isBadGateway());

        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(ANSWER_PATH)));
    }

    @Test
    @DisplayName("a downstream 422 becomes 502 with its detail preserved")
    void downstreamValidationErrorIsSurfaced() throws Exception {
        stubStatus(ANSWER_PATH, 422, "{\"detail\":\"as_of_date is required\"}");

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.downstream_status").value(422));
    }

    // -- malformed downstream output ---------------------------------------
    //
    // The assessment asks for a test double covering malformed model output,
    // and is explicit that it must be a technical FAILURE, never
    // INSUFFICIENT_EVIDENCE. Confusing the two would turn "our provider broke"
    // into "the policy does not cover this", which a reviewer acting on the
    // response could not tell apart.

    @ParameterizedTest(name = "malformed /answer body: {0}")
    @ValueSource(strings = {
            "this is not JSON at all",
            "{\"status\": \"ANSWERED\"",          // truncated mid-object
            "<html><body>502 Bad Gateway</body></html>",
            "",                                    // empty body
    })
    @DisplayName("malformed /answer output is 502, never a policy outcome")
    void malformedAnswerOutputIsATechnicalFailure(String body) throws Exception {
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo(ANSWER_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isBadGateway())
                // Must NOT be reported as a policy finding.
                .andExpect(jsonPath("$.status").value(502));

        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(ANSWER_PATH)));
    }

    @Test
    @DisplayName("malformed extraction output fails that item, not the batch")
    void malformedExtractionOutputFailsTheItem() throws Exception {
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo(EXTRACT_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ this is not valid json")));

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [{"filename": "a.txt", "document_id": "doc-001"}]
                }
                """;

        mockMvc.perform(multipart("/batches")
                        .file(new MockMultipartFile("metadata", "", "application/json",
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "certification INR 100".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.failed").value(1))
                .andExpect(jsonPath("$.results[0].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].error.code").value("EXTRACTION_FAILED"))
                // A technical failure leaves no policy finding behind.
                .andExpect(jsonPath("$.results[0].policy").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("malformed policy output fails the item with its own code")
    void malformedPolicyOutputFailsTheItem() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo(ANSWER_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("not json")));

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [{"filename": "a.txt", "document_id": "doc-001"}]
                }
                """;

        mockMvc.perform(multipart("/batches")
                        .file(new MockMultipartFile("metadata", "", "application/json",
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "certification INR 100".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].error.code").value("POLICY_LOOKUP_FAILED"))
                .andExpect(jsonPath("$.results[0].policy").value(Matchers.nullValue()))
                // The item still carries the invariants.
                .andExpect(jsonPath("$.results[0].review_required").value(true));
    }
}
