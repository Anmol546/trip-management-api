package com.anmol.trip.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/trips}.
 *
 * <p>{@code driverName} is optional at creation because a trip normally starts
 * in {@code CREATED} without a driver. It becomes mandatory as soon as the trip
 * moves to {@code ASSIGNED}, which is enforced by both the service layer and a
 * database check constraint.
 */
@Schema(name = "CreateTripRequest", description = "Fields required to create a trip")
public record CreateTripRequest(

        @Schema(description = "Caller supplied business key, unique across all trips. Case-insensitive.",
                example = "TRIP-2026-0001", maxLength = 50)
        @NotBlank(message = "tripNumber must not be blank")
        @Size(max = 50, message = "tripNumber must not exceed 50 characters")
        String tripNumber,

        @Schema(description = "Origin of the trip", example = "Delhi NCR", maxLength = 120)
        @NotBlank(message = "source must not be blank")
        @Size(max = 120, message = "source must not exceed 120 characters")
        String source,

        @Schema(description = "Final stop of the trip", example = "Jaipur", maxLength = 120)
        @NotBlank(message = "destination must not be blank")
        @Size(max = 120, message = "destination must not exceed 120 characters")
        String destination,

        @Schema(description = "Optional driver. Required before the trip can be ASSIGNED.",
                example = "Ravi Kumar", maxLength = 120)
        @Size(max = 120, message = "driverName must not exceed 120 characters")
        String driverName
) {
}
