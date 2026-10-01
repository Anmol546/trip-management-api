package com.anmol.trip.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * The same {@code Idempotency-Key} was replayed with a different request body.
 * Returning the first trip would be misleading, so this is a hard conflict.
 */
public class IdempotencyKeyConflictException extends ApiException {

    public IdempotencyKeyConflictException(String key) {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key '%s' was already used with a different request body".formatted(key),
                Map.of("idempotencyKey", key));
    }
}
