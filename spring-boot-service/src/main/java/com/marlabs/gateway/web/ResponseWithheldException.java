package com.marlabs.gateway.web;

/**
 * The assembled response violated an output invariant and was withheld.
 *
 * <p>Raised when the output guard finds claim-approval or payment language in a
 * downstream payload. Returning nothing is the correct outcome: an answer that
 * reads as an approval is worse than no answer, so this fails closed rather
 * than attempting to edit the text into something safe.
 */
public class ResponseWithheldException extends RuntimeException {

    public ResponseWithheldException(String message) {
        super(message);
    }
}
