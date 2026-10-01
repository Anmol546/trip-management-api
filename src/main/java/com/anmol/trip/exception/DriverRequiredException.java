package com.anmol.trip.exception;

import com.anmol.trip.domain.TripStatus;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A status that requires a driver was requested for a trip that has none. The
 * same rule is also enforced by a database check constraint, so it holds even if
 * another writer bypasses this service.
 */
public class DriverRequiredException extends ApiException {

    public DriverRequiredException(Long tripId, TripStatus target) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "DRIVER_REQUIRED",
                "Trip %d must have a driver before it can move to %s".formatted(tripId, target),
                Map.of("tripId", tripId, "requestedStatus", target.name()));
    }
}
