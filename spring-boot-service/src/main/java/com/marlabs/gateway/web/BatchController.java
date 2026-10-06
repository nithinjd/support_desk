package com.marlabs.gateway.web;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marlabs.gateway.auth.Caller;
import com.marlabs.gateway.auth.CallerAuthenticationFilter;
import com.marlabs.gateway.dto.BatchMetadata;
import com.marlabs.gateway.dto.BatchResponse;
import com.marlabs.gateway.service.BatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class BatchController {

    private static final Logger log = LoggerFactory.getLogger(BatchController.class);

    private final BatchService batchService;
    private final ObjectMapper objectMapper;

    public BatchController(BatchService batchService, ObjectMapper objectMapper) {
        this.batchService = batchService;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes a batch synchronously and returns one result per manifest entry.
     *
     * <p>The {@code metadata} part is taken as a {@link String} and parsed here
     * rather than letting Spring convert it directly. Clients commonly send that
     * part as {@code text/plain} (curl's {@code -F} does), which a typed
     * {@code @RequestPart} would reject with 415 before any of our validation
     * runs. Parsing by hand also lets malformed JSON return a 400 that says what
     * was wrong.
     *
     * @return HTTP 200 whenever the manifest is valid, regardless of how many
     *         individual items failed
     */
    @PostMapping(path = "/batches",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchResponse> createBatch(
            @RequestAttribute(CallerAuthenticationFilter.CALLER_ATTRIBUTE) Caller caller,
            @RequestPart("metadata") String metadataJson,
            @RequestPart(name = "files", required = false) List<MultipartFile> files) {

        BatchMetadata metadata;
        try {
            metadata = objectMapper.readValue(metadataJson, BatchMetadata.class);
        } catch (JsonProcessingException exc) {
            throw new BadRequestException(
                    "metadata part is not valid JSON: " + exc.getOriginalMessage());
        }

        log.info("POST /batches caller={} tenant={} documents={} parts={}",
                caller.callerId(), caller.tenant(),
                metadata.documents() == null ? 0 : metadata.documents().size(),
                files == null ? 0 : files.size());

        BatchResponse response = batchService.process(caller, metadata, files);

        log.info("Batch {} finished: {} completed, {} failed",
                response.batchId(), response.summary().completed(), response.summary().failed());

        return ResponseEntity.ok(response);
    }
}
