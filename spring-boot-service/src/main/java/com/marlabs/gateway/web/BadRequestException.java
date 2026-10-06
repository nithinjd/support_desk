package com.marlabs.gateway.web;

/** Client-side error in the request itself; always maps to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
