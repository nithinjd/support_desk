package com.marlabs.gateway.client;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.marlabs.gateway.auth.Caller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin client over the Python service's {@code /internal/*} endpoints.
 *
 * <p>Responses are passed through as {@link JsonNode} rather than mapped to
 * Java records. The gateway's job is authentication, batching and
 * orchestration, not re-describing the policy schema; a passthrough means a new
 * field added downstream reaches clients without a gateway change, and there is
 * no second copy of the contract to drift out of sync.
 */
@Component
public class PolicyServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PolicyServiceClient.class);

    private static final String ANSWER_PATH = "/internal/answer";
    private static final String EXTRACT_PATH = "/internal/extract";

    private final RestClient restClient;

    public PolicyServiceClient(RestClient policyServiceRestClient) {
        this.restClient = policyServiceRestClient;
    }

    /**
     * Asks for a grounded policy answer.
     *
     * <p>{@code tenant} and {@code role} are taken from the verified
     * {@link Caller} and never from client input, which is what makes the
     * tenant-override attempt in {@code request-05.txt} inert.
     */
    public JsonNode answer(Caller caller, String asOf, String requestedBenefit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", caller.tenant());
        body.put("role", caller.role());
        body.put("as_of_date", asOf);
        body.put("requested_benefit", requestedBenefit);

        return post(ANSWER_PATH, MediaType.APPLICATION_JSON, body);
    }

    /**
     * Sends raw bytes for deterministic extraction.
     *
     * <p>A 0-byte file is forwarded as-is: the extractor treats "nothing to
     * extract" as a valid record, and the gateway decides separately whether
     * that makes the batch item a failure.
     */
    public JsonNode extract(String filename, byte[] content, String contentType) {
        var fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                // Multipart requires a filename for the part to be treated as a
                // file upload rather than a plain form field.
                return filename == null || filename.isBlank() ? "upload.bin" : filename;
            }
        };

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", fileResource);

        return post(EXTRACT_PATH, MediaType.MULTIPART_FORM_DATA, parts);
    }

    private JsonNode post(String path, MediaType contentType, Object body) {
        try {
            var response = restClient.post()
                    .uri(path)
                    .contentType(contentType)
                    .body(body)
                    .retrieve()
                    // Do not let RestClient's default error handler throw: we
                    // want the downstream status and body in our own exception.
                    .onStatus(status -> status.isError(), (request, clientResponse) -> {
                        String errorBody = new String(
                                clientResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw PolicyServiceException.badResponse(
                                path, clientResponse.getStatusCode(), errorBody);
                    })
                    .body(JsonNode.class);

            if (response == null) {
                throw PolicyServiceException.badResponse(path, null, "empty response body");
            }
            return response;

        } catch (PolicyServiceException exc) {
            // Raised by the onStatus handler above; already carries the right
            // gateway status, so let it through untouched.
            throw exc;

        } catch (ResourceAccessException exc) {
            // Connect timeout, or the service is not listening at all. Nothing
            // is retried - this propagates on the first failure by design.
            log.warn("Policy service unreachable for {}: {}", path, exc.getMessage());
            throw PolicyServiceException.timeout(path, exc);

        } catch (RestClientException exc) {
            // A read timeout that trips while the response body is being
            // consumed arrives here rather than as ResourceAccessException, so
            // the cause chain has to be inspected: a socket timeout is still a
            // timeout and must report 504, not a generic 502.
            if (hasTimeoutCause(exc)) {
                log.warn("Policy service timed out reading response for {}: {}",
                        path, exc.getMessage());
                throw PolicyServiceException.timeout(path, exc);
            }
            log.warn("Policy service returned an unusable response for {}: {}",
                    path, exc.getMessage());
            throw PolicyServiceException.badResponse(path, null, exc.getMessage());
        }
    }

    /**
     * Looks through an exception's causes for a socket timeout.
     *
     * <p>Needed because the real cause is usually wrapped. A read timeout that
     * trips while we are parsing the response body arrives as a
     * RestClientException with the SocketTimeoutException buried one or two
     * levels down, so checking only the top-level type would miss it and we
     * would report 500 instead of 504.
     */
    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }

            // Guard against an exception listed as its own cause, which would
            // otherwise loop forever.
            if (cause == cause.getCause()) {
                break;
            }

            cause = cause.getCause();
        }

        return false;
    }
}
