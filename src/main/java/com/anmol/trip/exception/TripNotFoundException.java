package com.anmol.trip.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class TripNotFoundException extends ApiException {

    public TripNotFoundException(Long tripId) {
        super(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Trip %d does not exist".formatted(tripId),
                Map.of("tripId", tripId));
    }
}
