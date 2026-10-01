package com.anmol.trip.web.dto;

import com.anmol.trip.domain.Trip;
import com.anmol.trip.domain.TripStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "TripResponse", description = "A trip as returned by the API")
public record TripResponse(

        @Schema(example = "42") Long id,
        @Schema(example = "TRIP-2026-0001") String tripNumber,
        @Schema(example = "Delhi NCR") String source,
        @Schema(example = "Jaipur") String destination,
        @Schema(example = "Ravi Kumar") String driverName,
        @Schema(example = "IN_TRANSIT") TripStatus status,
        @Schema(description = "Optimistic lock counter. Echo it back if you need to reason about concurrent updates.",
                example = "3") Long version,
        @Schema(example = "2026-10-01T08:30:00Z") Instant createdAt,
        @Schema(example = "2026-10-01T09:05:00Z") Instant updatedAt
) {

    public static TripResponse from(Trip trip) {
        return new TripResponse(
                trip.getId(),
                trip.getTripNumber(),
                trip.getSource(),
                trip.getDestination(),
                trip.getDriverName(),
                trip.getStatus(),
                trip.getVersion(),
                trip.getCreatedAt(),
                trip.getUpdatedAt());
    }
}
