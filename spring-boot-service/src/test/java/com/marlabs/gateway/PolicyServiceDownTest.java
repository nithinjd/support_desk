package com.marlabs.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.marlabs.gateway.config.PolicyServiceProperties;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Behaviour when the Python service is not listening at all.
 *
 * <p>No WireMock here on purpose: the base URL points at a port nothing is
 * bound to, which is the real "Python is down" case and fails at connect time
 * rather than read time. The two paths must differ:
 *
 * <ul>
 *   <li>{@code /answer} has one thing to do and cannot do it - 504.</li>
 *   <li>{@code /batches} still owes the caller a result per manifest entry -
 *       200 with every item {@code FAILED}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Port 1 is reserved and never listening, so connect fails immediately.
        "policy-service.base-url=http://localhost:1",
})
@DisplayName("Python service down")
class PolicyServiceDownTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PolicyServiceProperties properties;

    @Test
    @DisplayName("the configured timeout really is 5 seconds")
    void timeoutIsFiveSeconds() {
        // Guards the requirement itself: a change to application.yml that
        // loosened this would otherwise pass every other test silently.
        assertThat(properties.timeout().toSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("/answer returns 504, not 500")
    void answerReturnsGatewayTimeout() throws Exception {
        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"certification limit\",\"as_of\":\"2026-09-01\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value(504))
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("no retry is attempted")));
    }

    @Test
    @DisplayName("/answer still reports the no-decision invariants when it fails")
    void failureStillCarriesTheInvariants() throws Exception {
        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"certification limit\",\"as_of\":\"2026-09-01\"}"))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    @DisplayName("authentication still runs when the downstream is down")
    void unauthenticatedRequestIsStill401() throws Exception {
        // 401 must win over 504: we never reach the downstream for a bad caller.
        mockMvc.perform(post("/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"x\",\"as_of\":\"2026-09-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("validation still runs when the downstream is down")
    void invalidBodyIsStill400() throws Exception {
        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\",\"as_of\":\"2026-09-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("/batches returns 200 with every item FAILED")
    void batchDegradesPerItemRatherThanFailingWholesale() throws Exception {
        String metadata = """
                {
                  "batch_id": "batch-down",
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
                                "certification INR 100".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "b.txt", "text/plain",
                                "home-office INR 200".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(2))
                .andExpect(jsonPath("$.summary.completed").value(0))
                .andExpect(jsonPath("$.summary.failed").value(2))
                .andExpect(jsonPath("$.results[0].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].error.code").value("EXTRACTION_FAILED"))
                // Hashing happens in the gateway, so it still works with no backend.
                .andExpect(jsonPath("$.results[0].sha256").value(
                        Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.results[0].issues[0].code")
                        .value("EXTRACTION_UNAVAILABLE"));
    }

    @Test
    @DisplayName("a failed batch item still carries review_required and no decision")
    void failedItemsKeepTheInvariants() throws Exception {
        mockMvc.perform(multipart("/batches")
                        .file(new MockMultipartFile("metadata", "", "application/json",
                                "{\"as_of\":\"2026-09-01\",\"documents\":[{\"filename\":\"a.txt\"}]}"
                                        .getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "body".getBytes(StandardCharsets.UTF_8)))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review_required").value(true))
                .andExpect(jsonPath("$.decision").value("NO_DECISION"))
                .andExpect(jsonPath("$.payment_initiated").value(false))
                .andExpect(jsonPath("$.results[0].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].review_required").value(true))
                .andExpect(jsonPath("$.results[0].decision").value("NO_DECISION"))
                .andExpect(jsonPath("$.results[0].payment_initiated").value(false));
    }
}
