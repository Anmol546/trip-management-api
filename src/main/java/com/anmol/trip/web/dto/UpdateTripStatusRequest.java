package com.anmol.trip.web.dto;

import com.anmol.trip.domain.TripStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Payload for {@code PATCH /api/v1/trips/{id}/status}. */
@Schema(name = "UpdateTripStatusRequest", description = "Target status and optional driver assignment")
public record UpdateTripStatusRequest(

        @Schema(description = "Desired status. The transition must be legal from the current status.",
                example = "ASSIGNED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "status is required")
        TripStatus status,

        @Schema(description = "Driver to assign to the trip. Required when moving to ASSIGNED, "
                + "IN_TRANSIT or COMPLETED if the trip does not already have one.",
                example = "Ravi Kumar", maxLength = 120)
        @Size(max = 120, message = "driverName must not exceed 120 characters")
        String driverName
) {
}
