package com.anmol.trip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * A trip between a source and a destination, owned by an optional driver.
 *
 * <p>{@link #version} carries the JPA optimistic lock. Status changes are not
 * applied through {@code saveAndFlush} on the managed entity; they go through a
 * conditional UPDATE that checks both the observed status and the observed
 * version, so two concurrent PATCH requests cannot both win. See
 * {@code TripRepository.updateStatus}.
 */
@Entity
@Table(name = "trips")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "trip_number", nullable = false, length = 50)
    private String tripNumber;

    @Column(name = "source", nullable = false, length = 120)
    private String source;

    @Column(name = "destination", nullable = false, length = 120)
    private String destination;

    @Column(name = "driver_name", length = 120)
    private String driverName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TripStatus status = TripStatus.CREATED;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Trip() {
        // for JPA
    }

    public Trip(String tripNumber, String source, String destination, Instant now) {
        this.tripNumber = tripNumber;
        this.source = source;
        this.destination = destination;
        this.status = TripStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTripNumber() {
        return tripNumber;
    }

    public String getSource() {
        return source;
    }

    public String getDestination() {
        return destination;
    }

    public String getDriverName() {
        return driverName;
    }

    public void setDriverName(String driverName) {
        this.driverName = driverName;
    }

    public TripStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Trip trip)) {
            return false;
        }
        return id != null && id.equals(trip.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
