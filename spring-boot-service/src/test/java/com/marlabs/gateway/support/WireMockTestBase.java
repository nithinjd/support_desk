package com.marlabs.gateway.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for gateway tests that need a stubbed Python service.
 *
 * <p>WireMock starts on a dynamic port in a static initialiser, before Spring
 * builds the context, so {@link DynamicPropertySource} can point
 * {@code policy-service.base-url} at it. One server is shared across all
 * subclasses and reset between tests - starting a fresh one per class would
 * leak ports and slow the suite for no benefit.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class WireMockTestBase {

    protected static final WireMockServer WIRE_MOCK =
            new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
        Runtime.getRuntime().addShutdownHook(new Thread(WIRE_MOCK::stop));
    }

    public static final String ANSWER_PATH = "/internal/answer";
    public static final String EXTRACT_PATH = "/internal/extract";

    @Autowired
    protected MockMvc mockMvc;

    @DynamicPropertySource
    static void policyServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("policy-service.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
    }

    @BeforeEach
    void resetStubs() {
        WIRE_MOCK.resetAll();
    }

    // -- stub helpers ------------------------------------------------------

    protected static void stubAnswer(String body) {
        WIRE_MOCK.stubFor(post(urlEqualTo(ANSWER_PATH)).willReturn(okJson(body)));
    }

    protected static void stubExtract(String body) {
        WIRE_MOCK.stubFor(post(urlEqualTo(EXTRACT_PATH)).willReturn(okJson(body)));
    }

    /** Stub that accepts the connection then stalls, to trip the read timeout. */
    protected static void stubDelayed(String path, int delayMillis) {
        WIRE_MOCK.stubFor(post(urlEqualTo(path)).willReturn(
                aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"TOO_LATE\"}")
                        .withFixedDelay(delayMillis)));
    }

    protected static void stubStatus(String path, int status, String body) {
        WIRE_MOCK.stubFor(post(urlEqualTo(path)).willReturn(
                aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(
            String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
