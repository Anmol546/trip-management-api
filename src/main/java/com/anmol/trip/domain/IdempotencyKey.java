package com.anmol.trip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Records that a client supplied {@code Idempotency-Key} on a create request.
 *
 * <p>The row is claimed before the trip is created. The unique constraint on
 * {@code idempotency_key} is what makes two concurrent retries of the same
 * request collapse into a single trip: the second claim fails, so it waits for
 * and replays the first attempt's result instead of creating a duplicate.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** Hash of the request payload, used to detect key reuse with a different body. */
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKey() {
        // for JPA
    }

    public IdempotencyKey(String idempotencyKey, String requestFingerprint, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
