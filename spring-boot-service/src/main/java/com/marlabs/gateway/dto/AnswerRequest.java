package com.marlabs.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /answer}.
 *
 * <p>Note what is absent: there is no tenant or role field. Identity comes from
 * the {@code X-Caller-Id} lookup alone, so a client cannot assert who it is.
 *
 * @param question free-text question, e.g. "What is my certification limit?"
 * @param asOf     evaluation date, {@code YYYY-MM-DD}
 */
public record AnswerRequest(

        @NotBlank(message = "question is required and must not be blank")
        @Size(max = 4000, message = "question must be at most 4000 characters")
        String question,

        // The regex rejects the shape; real-calendar validity (e.g. 2026-02-30)
        // is then checked by LocalDate.parse in the controller.
        @NotBlank(message = "as_of is required")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}",
                 message = "as_of must be a date in YYYY-MM-DD format")
        String asOf
) {
}
