package com.marlabs.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marlabs.gateway.support.StubPolicyService;
import com.marlabs.gateway.support.WireMockTestBase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Exact byte-duplicate detection within a batch.
 *
 * <p>The rule is identity of *content*, not of filename: two differently named
 * files with identical bytes are duplicates, and two identically named files
 * with different bytes are not. The first occurrence is canonical and keeps
 * {@code duplicate_of: null}; each later one points back to it.
 *
 * <p>The seeded corpus makes this concrete - {@code request-06.txt} is a
 * byte-for-byte copy of {@code request-01.txt}.
 */
@DisplayName("Batch duplicate detection")
class BatchDuplicateDetectionTest extends WireMockTestBase {

    private static final String CALLER = "atlas-employee-01";

    /** SHA-256 of the exact bytes seed_data.py writes into request-01.txt. */
    private static final String REQUEST_01_BODY =
            "Reference:\nCERT-101\nI request certification reimbursement of INR 18000 "
            + "for a completed cloud certification. Please tell me the applicable annual "
            + "limit and whether this request can be paid.";

    @Autowired
    private ObjectMapper objectMapper;

    private MockMultipartHttpServletRequestBuilder batch(String metadataJson,
                                                         MockMultipartFile... files) {
        var builder = multipart("/batches")
                .file(new MockMultipartFile("metadata", "", "application/json",
                        metadataJson.getBytes(StandardCharsets.UTF_8)));
        for (MockMultipartFile file : files) {
            builder.file(file);
        }
        builder.header("X-Caller-Id", CALLER);
        return builder;
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private void stubHappyPath() {
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));
    }

    // ---------------------------------------------------------------------

    @Test
    @DisplayName("identical bytes under different names are detected as duplicates")
    void identicalBytesAreDuplicates() throws Exception {
        stubHappyPath();

        String metadata = """
                {
                  "batch_id": "batch-dup-content",
                  "as_of": "2026-09-01",
                  "documents": [
                    {"filename": "request-01.txt", "document_id": "doc-001"},
                    {"filename": "request-06.txt", "document_id": "doc-006"}
                  ]
                }
                """;

        var response = mockMvc.perform(batch(metadata,
                        file("request-01.txt", REQUEST_01_BODY),
                        file("request-06.txt", REQUEST_01_BODY)))
                .andExpect(status().isOk())
                // The first occurrence is canonical.
                .andExpect(jsonPath("$.results[0].duplicate_of").value(
                        org.hamcrest.Matchers.nullValue()))
                // The second points back to it.
                .andExpect(jsonPath("$.results[1].duplicate_of").value("doc-001"))
                .andExpect(jsonPath("$.results[1].issues[?(@.code=='DUPLICATE_SUBMISSION')]")
                        .exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        String firstHash = body.at("/results/0/sha256").asText();
        String secondHash = body.at("/results/1/sha256").asText();

        assertThat(firstHash).isEqualTo(secondHash);
        assertThat(firstHash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("a one-byte difference is not a duplicate")
    void nearlyIdenticalFilesAreNotDuplicates() throws Exception {
        stubHappyPath();

        var response = mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"a.txt","document_id":"doc-001"},
                          {"filename":"b.txt","document_id":"doc-002"}]}
                        """,
                        file("a.txt", REQUEST_01_BODY),
                        file("b.txt", REQUEST_01_BODY + " ")))   // one trailing space
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].duplicate_of")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.results[1].duplicate_of")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.at("/results/0/sha256").asText())
                .isNotEqualTo(body.at("/results/1/sha256").asText());
    }

    @Test
    @DisplayName("three copies all point at the first, not at each other")
    void chainOfDuplicatesAllPointsAtTheFirst() throws Exception {
        stubHappyPath();

        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"a.txt","document_id":"doc-001"},
                          {"filename":"b.txt","document_id":"doc-002"},
                          {"filename":"c.txt","document_id":"doc-003"}]}
                        """,
                        file("a.txt", "same"), file("b.txt", "same"), file("c.txt", "same")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].duplicate_of")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.results[1].duplicate_of").value("doc-001"))
                .andExpect(jsonPath("$.results[2].duplicate_of").value("doc-001"));
    }

    @Test
    @DisplayName("duplicate detection follows manifest order")
    void canonicalIsTheFirstInManifestOrder() throws Exception {
        stubHappyPath();

        // "second.txt" appears first in the manifest, so it is canonical even
        // though "first.txt" was uploaded earlier in the multipart body.
        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"second.txt","document_id":"doc-S"},
                          {"filename":"first.txt","document_id":"doc-F"}]}
                        """,
                        file("first.txt", "same"), file("second.txt", "same")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].document_id").value("doc-S"))
                .andExpect(jsonPath("$.results[0].duplicate_of")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.results[1].duplicate_of").value("doc-S"));
    }

    @Test
    @DisplayName("a duplicate is still processed, not skipped")
    void duplicatesAreStillProcessed() throws Exception {
        stubHappyPath();

        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"a.txt","document_id":"doc-001"},
                          {"filename":"b.txt","document_id":"doc-002"}]}
                        """,
                        file("a.txt", "same"), file("b.txt", "same")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.completed").value(2))
                // The duplicate carries its own full answer, not a stub.
                .andExpect(jsonPath("$.results[1].policy.status").value("ANSWERED"))
                .andExpect(jsonPath("$.results[1].extracted.benefit").value("CERTIFICATION"));
    }

    @Test
    @DisplayName("two empty files are duplicates of each other")
    void emptyFilesShareTheWellKnownEmptyHash() throws Exception {
        stubHappyPath();

        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"e1.txt","document_id":"doc-001"},
                          {"filename":"e2.txt","document_id":"doc-002"}]}
                        """,
                        file("e1.txt", ""), file("e2.txt", "")))
                .andExpect(status().isOk())
                // SHA-256 of the empty byte string.
                .andExpect(jsonPath("$.results[0].sha256").value(
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
                .andExpect(jsonPath("$.results[1].duplicate_of").value("doc-001"))
                // Both still fail: duplicate_of does not rescue an empty file.
                .andExpect(jsonPath("$.summary.failed").value(2));
    }

    @Test
    @DisplayName("the first occurrence stays canonical even when it fails")
    void failedFirstOccurrenceIsStillCanonical() throws Exception {
        stubHappyPath();

        // Both are empty, so doc-001 fails; doc-002 must still reference it
        // rather than claiming to be the original.
        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"e1.txt","document_id":"doc-001"},
                          {"filename":"e2.txt","document_id":"doc-002"}]}
                        """,
                        file("e1.txt", ""), file("e2.txt", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].duplicate_of").value("doc-001"));
    }

    @Test
    @DisplayName("sha256 is reported for every item that had bytes")
    void everyItemReportsItsHash() throws Exception {
        stubHappyPath();

        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"a.txt"},{"filename":"b.txt"}]}
                        """, file("a.txt", "aaa"), file("b.txt", "bbb")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].sha256").value(
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.results[1].sha256").value(
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    @DisplayName("sha256 matches the known digest of a fixed input")
    void hashMatchesKnownValue() throws Exception {
        stubHappyPath();

        // Independently verifiable: echo -n "abc" | sha256sum
        mockMvc.perform(batch("{\"as_of\":\"2026-09-01\",\"documents\":[{\"filename\":\"a.txt\"}]}",
                        file("a.txt", "abc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].sha256").value(
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    }
}
