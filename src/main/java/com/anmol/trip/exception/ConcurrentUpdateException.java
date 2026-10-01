package com.anmol.trip.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Raised when a status update lost its compare-and-set race on every attempt.
 * In practice this only happens when a trip is being hammered by concurrent
 * writers, and telling the caller to retry with fresh state is the correct
 * answer rather than guessing a final status.
 */
public class ConcurrentUpdateException extends ApiException {

    public ConcurrentUpdateException(Long tripId, int attempts) {
        super(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                "Trip %d is being modified concurrently; the status update was not applied after %d attempts. "
                        .formatted(tripId, attempts) + "Retry with a fresh GET.",
                Map.of("tripId", tripId, "attempts", attempts));
    }
}
