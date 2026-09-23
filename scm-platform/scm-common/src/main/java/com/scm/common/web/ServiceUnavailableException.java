package com.scm.common.web;

/** A downstream service needed to complete the request is unavailable (circuit open, timeout, error). */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
