package com.anmol.trip.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A previous request claimed this {@code Idempotency-Key} and has not finished
 * yet. The duplicate caller waited for it and gave up, which is safer than
 * creating a second trip.
 */
public class IdempotentRequestInProgressException extends ApiException {

    public IdempotentRequestInProgressException(String key) {
        super(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_IN_PROGRESS",
                "Another request with Idempotency-Key '%s' is still being processed. Retry shortly.".formatted(key),
                Map.of("idempotencyKey", key));
    }
}
