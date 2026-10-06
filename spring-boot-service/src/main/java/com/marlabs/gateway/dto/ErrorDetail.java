package com.marlabs.gateway.dto;

/**
 * Why one batch item failed.
 *
 * <p>The assessment asks for "a stable code and safe message". Stable means a
 * client can branch on {@link #code} without parsing English, so the codes are
 * fixed strings that do not change when the wording does. Safe means the
 * message never repeats document contents - a failed claim form may hold
 * personal information, and an error string is exactly the sort of value that
 * ends up in a log aggregator.
 *
 * <p>{@code null} on a completed item.
 *
 * @param code    stable identifier, e.g. {@code EMPTY_FILE}
 * @param message one human-readable sentence, free of document content
 */
public record ErrorDetail(String code, String message) {

    /** The manifest named a file that was not uploaded. */
    public static final String MISSING_UPLOAD = "MISSING_UPLOAD";

    /** The uploaded file contained zero bytes. */
    public static final String EMPTY_FILE = "EMPTY_FILE";

    /** The file had bytes but no readable text - scanned or corrupt. */
    public static final String NO_EXTRACTABLE_TEXT = "NO_EXTRACTABLE_TEXT";

    /** The uploaded bytes could not be read from the request. */
    public static final String UPLOAD_UNREADABLE = "UPLOAD_UNREADABLE";

    /** The extraction service timed out, refused, or returned nonsense. */
    public static final String EXTRACTION_FAILED = "EXTRACTION_FAILED";

    /** The policy service timed out, refused, or returned nonsense. */
    public static final String POLICY_LOOKUP_FAILED = "POLICY_LOOKUP_FAILED";

    /** The downstream response asserted a claim approval and was withheld. */
    public static final String OUTPUT_WITHHELD = "OUTPUT_WITHHELD";

    public static ErrorDetail of(String code, String message) {
        return new ErrorDetail(code, message);
    }
}
