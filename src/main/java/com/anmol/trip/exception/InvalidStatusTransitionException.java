package com.anmol.trip.exception;

import com.anmol.trip.domain.TripStatus;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;

/**
 * Raised when a caller asks for a transition the lifecycle does not allow, for
 * example moving backwards or leaving a terminal status.
 */
public class InvalidStatusTransitionException extends ApiException {

    public InvalidStatusTransitionException(Long tripId, TripStatus from, TripStatus to) {
        super(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                "Trip %d cannot move from %s to %s. Allowed targets from %s: %s"
                        .formatted(tripId, from, to, from, describe(from.allowedTargets())),
                Map.of(
                        "tripId", tripId,
                        "currentStatus", from.name(),
                        "requestedStatus", to.name(),
                        "allowedTransitions", describe(from.allowedTargets())));
    }

    private static String describe(Set<TripStatus> allowed) {
        return allowed.isEmpty()
                ? "none, this status is terminal"
                : String.join(", ", allowed.stream().map(Enum::name).toList());
    }
}
