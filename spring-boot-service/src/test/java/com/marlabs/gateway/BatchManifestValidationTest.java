package com.marlabs.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marlabs.gateway.support.StubPolicyService;
import com.marlabs.gateway.support.WireMockTestBase;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Manifest validation for {@code POST /batches}.
 *
 * <p>The dividing line under test: a manifest that is *ambiguous* fails the
 * whole request with 400, while a manifest that is merely *unsatisfiable for
 * one entry* returns 200 and fails that entry. Duplicated filenames are the
 * ambiguous case - two entries with one name leave no way to decide which
 * uploaded part belongs to which entry.
 */
@DisplayName("POST /batches manifest validation")
class BatchManifestValidationTest extends WireMockTestBase {

    private static final String CALLER = "atlas-employee-01";

    private MockMultipartHttpServletRequestBuilder batch(String metadataJson,
                                                         MockMultipartFile... files) {
        var builder = multipart("/batches")
                .file(new MockMultipartFile("metadata", "", "application/json",
                        metadataJson.getBytes()));
        for (MockMultipartFile file : files) {
            builder.file(file);
        }
        builder.header("X-Caller-Id", CALLER);
        return builder;
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain", content.getBytes());
    }

    // -- 400: ambiguous or unusable manifests ------------------------------

    @Test
    @DisplayName("duplicate filenames in the manifest are 400")
    void duplicateFilenamesAreRejected() throws Exception {
        String metadata = """
                {
                  "batch_id": "batch-dup",
                  "as_of": "2026-09-01",
                  "documents": [
                    {"filename": "request-01.txt"},
                    {"filename": "request-01.txt"}
                  ]
                }
                """;

        mockMvc.perform(batch(metadata, file("request-01.txt", "body")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("duplicate filenames in metadata.documents")))
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("request-01.txt")));
    }

    @Test
    @DisplayName("three-way duplicate names report every repeated name")
    void allDuplicateNamesAreNamed() throws Exception {
        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [
                    {"filename": "a.txt"}, {"filename": "b.txt"},
                    {"filename": "a.txt"}, {"filename": "b.txt"}
                  ]
                }
                """;

        mockMvc.perform(batch(metadata))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("a.txt")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("b.txt")));
    }

    @Test
    @DisplayName("duplicate document_id is 400")
    void duplicateDocumentIdIsRejected() throws Exception {
        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [
                    {"filename": "a.txt", "document_id": "doc-001"},
                    {"filename": "b.txt", "document_id": "doc-001"}
                  ]
                }
                """;

        mockMvc.perform(batch(metadata))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("duplicate document_id")));
    }

    @Test
    @DisplayName("empty documents array is 400")
    void emptyManifestIsRejected() throws Exception {
        mockMvc.perform(batch("{\"as_of\":\"2026-09-01\",\"documents\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("at least one entry")));
    }

    @Test
    @DisplayName("absent documents array is 400")
    void missingDocumentsIsRejected() throws Exception {
        mockMvc.perform(batch("{\"as_of\":\"2026-09-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an entry with no filename is 400")
    void entryWithoutFilenameIsRejected() throws Exception {
        mockMvc.perform(batch("{\"as_of\":\"2026-09-01\",\"documents\":[{\"document_id\":\"d1\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("needs a filename")));
    }

    @Test
    @DisplayName("missing as_of is 400")
    void missingAsOfIsRejected() throws Exception {
        mockMvc.perform(batch("{\"documents\":[{\"filename\":\"a.txt\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("metadata.as_of is required")));
    }

    @Test
    @DisplayName("wrongly formatted as_of is 400")
    void badAsOfFormatIsRejected() throws Exception {
        mockMvc.perform(batch(
                        "{\"as_of\":\"01-09-2026\",\"documents\":[{\"filename\":\"a.txt\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("YYYY-MM-DD")));
    }

    @Test
    @DisplayName("a date that does not exist is 400")
    void impossibleDateIsRejected() throws Exception {
        mockMvc.perform(batch(
                        "{\"as_of\":\"2026-02-30\",\"documents\":[{\"filename\":\"a.txt\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("malformed metadata JSON is 400, not 500")
    void malformedMetadataJsonIsRejected() throws Exception {
        mockMvc.perform(batch("{\"as_of\":\"2026-09-01\",\"documents\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("not valid JSON")));
    }

    @Test
    @DisplayName("absent metadata part is 400")
    void missingMetadataPartIsRejected() throws Exception {
        mockMvc.perform(multipart("/batches")
                        .file(file("a.txt", "body"))
                        .header("X-Caller-Id", CALLER))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("nothing is processed when the manifest is rejected")
    void rejectedManifestCallsNoDownstream() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));

        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"a.txt"},{"filename":"a.txt"}]}
                        """, file("a.txt", "body")))
                .andExpect(status().isBadRequest());

        WIRE_MOCK.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo(EXTRACT_PATH)));
    }

    // -- 200: valid manifest, item-level problems --------------------------

    @Test
    @DisplayName("a manifest entry with no uploaded file is 400 for the whole batch")
    void missingFilePartFailsTheWholeBatch() throws Exception {
        // The spec treats a missing part as a mismatch between what the client
        // thinks it sent and what arrived, so the whole request is rejected
        // rather than one item being marked FAILED.
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [
                    {"filename": "present.txt", "document_id": "doc-001"},
                    {"filename": "absent.txt",  "document_id": "doc-002"}
                  ]
                }
                """;

        mockMvc.perform(batch(metadata, file("present.txt", "certification INR 18000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("missing file parts")))
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("absent.txt")));

        // Nothing is processed when the request itself is rejected.
        WIRE_MOCK.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo(EXTRACT_PATH)));
    }

    @Test
    @DisplayName("an uploaded file not named in the manifest is 400")
    void extraFilePartFailsTheWholeBatch() throws Exception {
        // An extra part means the client believes a document was processed
        // that we would otherwise silently ignore.
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [{"filename": "listed.txt", "document_id": "doc-001"}]
                }
                """;

        mockMvc.perform(batch(metadata,
                        file("listed.txt", "certification INR 18000"),
                        file("stowaway.txt", "certification INR 999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("not listed in metadata.documents")))
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("stowaway.txt")));
    }

    @Test
    @DisplayName("matching parts and manifest entries proceed normally")
    void exactlyMatchingPartsAreAccepted() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));

        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"a.txt","document_id":"doc-001"},
                          {"filename":"b.txt","document_id":"doc-002"}]}
                        """, file("a.txt", "aaa"), file("b.txt", "bbb")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(2))
                .andExpect(jsonPath("$.summary.completed").value(2))
                .andExpect(jsonPath("$.summary.failed").value(0));
    }

    @Test
    @DisplayName("results follow manifest order, not upload order")
    void resultsAreInManifestOrder() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(1.0, "CERT-1"));
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [
                    {"filename": "third.txt"},
                    {"filename": "first.txt"},
                    {"filename": "second.txt"}
                  ]
                }
                """;

        // Uploaded in a different order on purpose.
        mockMvc.perform(batch(metadata,
                        file("first.txt", "aaa"),
                        file("second.txt", "bbb"),
                        file("third.txt", "ccc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].filename").value("third.txt"))
                .andExpect(jsonPath("$.results[1].filename").value("first.txt"))
                .andExpect(jsonPath("$.results[2].filename").value("second.txt"));
    }

    @Test
    @DisplayName("document_id is generated when omitted")
    void documentIdsAreGenerated() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(1.0, "CERT-1"));
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));

        mockMvc.perform(batch("""
                        {"as_of":"2026-09-01","documents":[
                          {"filename":"a.txt"},{"filename":"b.txt"}]}
                        """, file("a.txt", "aaa"), file("b.txt", "bbb")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].document_id").value("doc-001"))
                .andExpect(jsonPath("$.results[1].document_id").value("doc-002"));
    }

    @Test
    @DisplayName("batch_id is generated when omitted")
    void batchIdIsGenerated() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(1.0, "CERT-1"));
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));

        mockMvc.perform(batch("{\"as_of\":\"2026-09-01\",\"documents\":[{\"filename\":\"a.txt\"}]}",
                        file("a.txt", "aaa")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_id").value(Matchers.startsWith("batch-")));
    }

    @Test
    @DisplayName("a 0-byte file fails its item without failing the batch")
    void zeroByteFileFailsOneItem() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(18000.0, "CERT-101"));
        stubAnswer(StubPolicyService.answered("Atlas", "employee", 25000.0));

        String metadata = """
                {
                  "as_of": "2026-09-01",
                  "documents": [
                    {"filename": "good.txt", "document_id": "doc-001"},
                    {"filename": "empty.txt", "document_id": "doc-002"}
                  ]
                }
                """;

        mockMvc.perform(batch(metadata,
                        file("good.txt", "certification INR 18000"),
                        file("empty.txt", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.completed").value(1))
                .andExpect(jsonPath("$.summary.failed").value(1))
                .andExpect(jsonPath("$.results[1].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].size_bytes").value(0))
                .andExpect(jsonPath("$.results[1].error.code").value("EMPTY_FILE"))
                .andExpect(jsonPath("$.results[1].error.message").value(
                        Matchers.containsString("zero bytes")))
                .andExpect(jsonPath("$.results[0].error").value(
                        Matchers.nullValue()))
                .andExpect(jsonPath("$.results[1].issues[0].code")
                        .value("NO_EXTRACTABLE_CONTENT"));
    }

    @Test
    @DisplayName("an empty file is never sent downstream")
    void emptyFileIsNotSentForExtraction() throws Exception {
        stubExtract(StubPolicyService.extractionCertification(1.0, "CERT-1"));

        mockMvc.perform(batch("{\"as_of\":\"2026-09-01\",\"documents\":[{\"filename\":\"e.txt\"}]}",
                        file("e.txt", "")))
                .andExpect(status().isOk());

        WIRE_MOCK.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo(EXTRACT_PATH)));
    }
}
