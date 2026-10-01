package com.anmol.trip.exception;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for failures that map to a specific HTTP status and a stable
 * machine readable {@code code}. Anything thrown out of the web layer that is
 * not an {@code ApiException} is treated as an unexpected internal error.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    protected ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    protected ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Stable, machine readable error code, e.g. {@code TRIP_NOT_FOUND}. */
    public String getCode() {
        return code;
    }

    /** Extra context rendered into the error body, e.g. the current status. */
    public Map<String, Object> getDetails() {
        return details;
    }
}
