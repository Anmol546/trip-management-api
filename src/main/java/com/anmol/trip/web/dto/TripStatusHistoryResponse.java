package com.anmol.trip.web.dto;

import com.anmol.trip.domain.TripStatus;
import com.anmol.trip.domain.TripStatusHistory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "TripStatusHistoryResponse", description = "A single recorded status transition")
public record TripStatusHistoryResponse(
        Long id,
        @Schema(description = "Null for the implicit CREATED entry that starts the audit trail",
                example = "CREATED") TripStatus fromStatus,
        @Schema(example = "ASSIGNED") TripStatus toStatus,
        String changedBy,
        @Schema(example = "2026-10-01T09:05:00Z") Instant changedAt
) {

    public static TripStatusHistoryResponse from(TripStatusHistory entry) {
        return new TripStatusHistoryResponse(
                entry.getId(),
                entry.getFromStatus(),
                entry.getToStatus(),
                entry.getChangedBy(),
                entry.getChangedAt());
    }
}
