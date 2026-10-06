package com.marlabs.gateway.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.marlabs.gateway.auth.Caller;
import com.marlabs.gateway.auth.CallerAuthenticationFilter;
import com.marlabs.gateway.client.PolicyServiceClient;
import com.marlabs.gateway.dto.AnswerRequest;
import com.marlabs.gateway.service.OutputGuard;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnswerController {

    private static final Logger log = LoggerFactory.getLogger(AnswerController.class);

    private final PolicyServiceClient policyService;
    private final OutputGuard outputGuard;

    public AnswerController(PolicyServiceClient policyService, OutputGuard outputGuard) {
        this.policyService = policyService;
        this.outputGuard = outputGuard;
    }

    /**
     * Answers a single policy question for the authenticated caller.
     *
     * <p>The {@link Caller} is injected from the request attribute set by
     * {@link CallerAuthenticationFilter}; by the time this method runs the
     * caller is already known to be valid, since an unauthenticated request was
     * rejected with 401 in the filter chain.
     */
    @PostMapping(path = "/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> answer(
            @RequestAttribute(CallerAuthenticationFilter.CALLER_ATTRIBUTE) Caller caller,
            @Valid @RequestBody AnswerRequest request) {

        // Two-stage date validation. The @Pattern annotation on the DTO
        // already rejected anything not shaped like YYYY-MM-DD. This catches
        // strings with the right shape but no real date behind them, such as
        // 2026-02-30 - February never has 30 days, but the regex cannot know
        // that, so only an actual parse attempt catches it.
        String asOf;
        try {
            asOf = LocalDate.parse(request.asOf().trim()).toString();
        } catch (DateTimeParseException exc) {
            throw new BadRequestException(
                    "as_of is not a valid calendar date: " + request.asOf());
        }

        log.info("POST /answer caller={} tenant={} role={} as_of={}",
                caller.callerId(), caller.tenant(), caller.role(), asOf);

        // We forward the question text as `requested_benefit` and let the
        // Python service classify it. That keeps the benefit vocabulary in one
        // place instead of maintaining a second copy here that could drift.
        //
        // IDENTITY IS PINNED TO THE HEADER. Note what we pass: `caller`, which
        // came from the X-Caller-Id lookup. The question text has no influence
        // on tenant or role at all. A question saying "I belong to Boreal" is
        // just text to be classified - there is no code path from the request
        // body to the identity we answer under.
        JsonNode body = policyService.answer(caller, asOf, request.question());

        // Last check before the response leaves the gateway: refuse to pass on
        // anything that reads as approving a claim. See OutputGuard.
        List<String> violations = outputGuard.scan(body);
        if (!violations.isEmpty()) {
            log.error("Blocked approval language from downstream on /answer: {}", violations);
            throw new ResponseWithheldException(
                    "Response withheld: the policy service returned claim-approval language, "
                            + "which this system must never emit. Fields: "
                            + String.join(", ", violations));
        }

        return ResponseEntity.ok(body);
    }
}
