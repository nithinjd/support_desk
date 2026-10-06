package com.marlabs.gateway.dto;

import java.util.List;

/**
 * The {@code metadata} part of a {@code POST /batches} multipart request.
 *
 * <p>Expected JSON:
 * <pre>
 * {
 *   "batch_id": "batch-001",
 *   "as_of": "2026-09-01",
 *   "documents": [
 *     {"filename": "request-01.txt", "document_id": "doc-001"},
 *     {"filename": "request-06.txt"}
 *   ]
 * }
 * </pre>
 *
 * <p>The {@code documents} array is the manifest: it fixes both the set of
 * expected files and the order results are returned in. {@code document_id} is
 * optional and generated when omitted.
 */
public record BatchMetadata(String batchId, String asOf, List<DocumentEntry> documents) {

    public record DocumentEntry(String filename, String documentId) {
    }
}
