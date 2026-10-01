package com.anmol.trip.exception;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when a trip number is already taken. This is the safety net for
 * duplicate creates that arrive without an {@code Idempotency-Key}: the unique
 * index on {@code trips.trip_number} turns a race into a clean 409 instead of
 * two identical rows.
 */
public class DuplicateTripNumberException extends ApiException {

    /**
     * @param existingTripId id of the trip that already holds the number, or
     *                       {@code null} when the race was only detected by the
     *                       database and the lookup could not be performed in
     *                       the rolled back transaction
     */
    public DuplicateTripNumberException(String tripNumber, Long existingTripId) {
        super(HttpStatus.CONFLICT, "TRIP_NUMBER_ALREADY_EXISTS",
                "A trip with tripNumber '%s' already exists".formatted(tripNumber),
                buildDetails(tripNumber, existingTripId));
    }

    private static Map<String, Object> buildDetails(String tripNumber, Long existingTripId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tripNumber", tripNumber);
        if (existingTripId != null) {
            details.put("existingTripId", existingTripId);
        }
        return details;
    }
}
