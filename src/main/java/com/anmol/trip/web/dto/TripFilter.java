package com.anmol.trip.web.dto;

import com.anmol.trip.domain.TripStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Query filters for {@code GET /api/v1/trips}. Every field is optional and they
 * combine with AND.
 */
@Schema(name = "TripFilter", description = "Optional filters applied to the trip listing")
public record TripFilter(

        @Schema(description = "Only return trips in this status. Repeat the parameter to filter on several statuses.",
                example = "IN_TRANSIT") TripStatus status,
        @Schema(description = "Case-insensitive exact match on origin", example = "Delhi NCR") String source,
        @Schema(description = "Case-insensitive exact match on destination", example = "Jaipur") String destination,
        @Schema(description = "Case-insensitive exact match on trip number", example = "TRIP-2026-0001") String tripNumber,
        @Schema(description = "Inclusive lower bound on createdAt (ISO-8601)", example = "2026-10-01T00:00:00Z") Instant createdFrom,
        @Schema(description = "Exclusive upper bound on createdAt (ISO-8601)", example = "2026-11-01T00:00:00Z") Instant createdTo
) {
}
