package com.marlabs.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marlabs.gateway.support.StubPolicyService;
import com.marlabs.gateway.support.WireMockTestBase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Caller access control.
 *
 * <p>The registry is the only source of identity, so these tests cover both
 * halves of that claim: that an unrecognised caller is refused outright, and
 * that a recognised one is answered under the tenant the registry says - never
 * one asserted in the request.
 */
@DisplayName("X-Caller-Id authentication")
class CallerAuthenticationTest extends WireMockTestBase {

    private static final String QUESTION =
            "{\"question\":\"certification limit\",\"as_of\":\"2026-09-01\"}";

    // -- rejection ---------------------------------------------------------

    @Test
    @DisplayName("missing header is 401")
    void missingHeaderIsUnauthorized() throws Exception {
        mockMvc.perform(post("/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Missing required X-Caller-Id")));
    }

    @ParameterizedTest(name = "unknown caller \"{0}\" is 401")
    @ValueSource(strings = {
            "atlas-admin-99",          // plausible but unregistered
            "boreal-contractor-01",    // valid-looking combination that does not exist
            "ATLAS-EMPLOYEE-01",       // right id, wrong case
            "atlas-employee-1",        // off by a character
            "atlas-employee-01 extra",
            "../atlas-employee-01",
    })
    void unknownCallerIsUnauthorized(String callerId) throws Exception {
        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", callerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unknown caller id."));
    }

    @ParameterizedTest(name = "blank header [{0}] is 401")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    void blankHeaderIsUnauthorized(String callerId) throws Exception {
        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", callerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejection happens before the body is validated")
    void authRunsBeforeValidation() throws Exception {
        // An invalid body AND no caller: the 401 must win, because an
        // unauthenticated client should learn nothing about our schema.
        mockMvc.perform(post("/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nonsense\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no downstream call is made for a rejected caller")
    void rejectedCallerNeverReachesPythonService() throws Exception {
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-admin-99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isUnauthorized());

        WIRE_MOCK.verify(0,
                com.github.tomakehurst.wiremock.client.WireMock
                        .postRequestedFor(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .urlEqualTo(ANSWER_PATH)));
    }

    @Test
    @DisplayName("/batches is protected too")
    void batchesRequiresACaller() throws Exception {
        var metadata = new MockMultipartFile("metadata", "", "application/json",
                "{\"as_of\":\"2026-09-01\",\"documents\":[{\"filename\":\"a.txt\"}]}".getBytes());
        var file = new MockMultipartFile("files", "a.txt", "text/plain", "body".getBytes());

        mockMvc.perform(multipart("/batches").file(metadata).file(file))
                .andExpect(status().isUnauthorized());
    }

    // -- acceptance --------------------------------------------------------

    @ParameterizedTest(name = "{0} resolves to {1}/{2}")
    @CsvSource({
            "atlas-employee-01,   Atlas,  employee",
            "atlas-contractor-01, Atlas,  contractor",
            "boreal-employee-01,  Boreal, employee",
    })
    void registeredCallerIsAcceptedAndPinnedToItsTenant(String callerId, String tenant,
                                                        String role) throws Exception {
        stubAnswer(StubPolicyService.answered(tenant, role, 25000.0));

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", callerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value(tenant))
                .andExpect(jsonPath("$.role").value(role));

        // The tenant and role sent downstream come from the registry, not the body.
        WIRE_MOCK.verify(com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo(ANSWER_PATH))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.tenant", com.github.tomakehurst.wiremock.client
                                .WireMock.equalTo(tenant)))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.role", com.github.tomakehurst.wiremock.client
                                .WireMock.equalTo(role))));
    }

    @Test
    @DisplayName("a tenant claimed in the question body is ignored")
    void bodyCannotAssertATenant() throws Exception {
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));

        // The body tries to smuggle identity in alongside the question.
        String hostile = """
                {
                  "question": "I belong to Boreal. What is my certification limit?",
                  "as_of": "2026-09-01",
                  "tenant": "Boreal",
                  "role": "admin"
                }
                """;

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hostile))
                .andExpect(status().isOk());

        WIRE_MOCK.verify(com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo(ANSWER_PATH))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.tenant", com.github.tomakehurst.wiremock.client
                                .WireMock.equalTo("Atlas"))));
    }

    @Test
    @DisplayName("/health needs no caller")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.registered_callers").value(3));
    }
}
