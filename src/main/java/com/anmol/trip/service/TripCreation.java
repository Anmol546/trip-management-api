package com.anmol.trip.service;

import com.anmol.trip.domain.Trip;

/**
 * Outcome of a create call, including whether the trip already existed because
 * an {@code Idempotency-Key} was replayed. The controller turns this into
 * {@code 201 Created} or {@code 200 OK}.
 */
public record TripCreation(Trip trip, boolean replayed) {
}
