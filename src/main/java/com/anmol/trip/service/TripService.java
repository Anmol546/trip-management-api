package com.anmol.trip.service;

import com.anmol.trip.domain.Trip;
import com.anmol.trip.domain.TripStatus;
import com.anmol.trip.domain.TripStatusHistory;
import com.anmol.trip.exception.ConcurrentUpdateException;
import com.anmol.trip.exception.DriverRequiredException;
import com.anmol.trip.exception.DuplicateTripNumberException;
import com.anmol.trip.exception.InvalidStatusTransitionException;
import com.anmol.trip.exception.TripNotFoundException;
import com.anmol.trip.repository.TripRepository;
import com.anmol.trip.repository.TripSpecifications;
import com.anmol.trip.repository.TripStatusHistoryRepository;
import com.anmol.trip.web.dto.CreateTripRequest;
import com.anmol.trip.web.dto.TripFilter;
import com.anmol.trip.web.dto.UpdateTripStatusRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Business rules for trips.
 *
 * <p>Two mechanisms keep concurrent writers honest:
 *
 * <ul>
 *   <li>creates are de-duplicated by the unique index on {@code trip_number},
 *       optionally fronted by an {@code Idempotency-Key};</li>
 *   <li>status changes go through a conditional UPDATE that carries both the
 *       observed status and the observed version, so exactly one of several
 *       concurrent writers can apply a given transition.</li>
 * </ul>
 */
@Service
public class TripService {

    private static final Logger log = LoggerFactory.getLogger(TripService.class);

    /**
     * How many times a status update re-reads the trip and retries after losing
     * the compare-and-set race. One attempt is enough when writers are spread
     * out; three absorbs a burst without spinning.
     */
    static final int MAX_STATUS_ATTEMPTS = 3;

    private final TripRepository trips;
    private final TripStatusHistoryRepository history;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TripService(TripRepository trips, TripStatusHistoryRepository history,
                       IdempotencyService idempotency, Clock clock) {
        this.trips = trips;
        this.history = history;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a trip.
     *
     * <p>Without an {@code Idempotency-Key} the trip number itself is the
     * idempotency token and a repeat create returns {@code 409}. With a key, a
     * repeat call replays the original trip and returns {@code 200}, which is
     * what makes client side retries and double taps safe.
     */
    @Transactional
    public TripCreation create(CreateTripRequest request, String idempotencyKeyHeader, String actor) {
        CreateTripRequest normalized = TripRequestNormalizer.normalize(request);
        String key = TripRequestNormalizer.normalizeOptional(idempotencyKeyHeader);

        if (key == null) {
            return new TripCreation(insert(normalized, actor), false);
        }

        String fingerprint = TripRequestNormalizer.fingerprint(normalized);
        IdempotencyService.Claim claim = idempotency.claim(key, fingerprint);

        if (!claim.claimed()) {
            log.info("Replaying trip {} for idempotency key {}", claim.tripId(), key);
            return new TripCreation(findById(claim.tripId()), true);
        }

        try {
            Trip trip = insert(normalized, actor);
            idempotency.complete(key, trip.getId());
            return new TripCreation(trip, false);
        } catch (RuntimeException e) {
            // Free the key so the client can retry rather than being locked out
            // by a claim that will never complete.
            idempotency.release(key);
            throw e;
        }
    }

    private Trip insert(CreateTripRequest request, String actor) {
        // Fast path: report the existing id. The database is still the authority
        // below, this only avoids burning a transaction on the common case.
        trips.findByTripNumberIgnoreCase(request.tripNumber()).ifPresent(existing -> {
            throw new DuplicateTripNumberException(request.tripNumber(), existing.getId());
        });

        Instant now = Instant.now(clock);
        Trip trip = new Trip(request.tripNumber(), request.source(), request.destination(), now);
        trip.setDriverName(request.driverName());

        Trip saved;
        try {
            saved = trips.saveAndFlush(trip);
        } catch (DataIntegrityViolationException e) {
            // Lost the pre-check race. The unique index did its job.
            throw new DuplicateTripNumberException(request.tripNumber(), null);
        }

        history.save(new TripStatusHistory(saved.getId(), null, TripStatus.CREATED, actor, now));
        return saved;
    }

    // ------------------------------------------------------------------ status

    /**
     * Applies a status transition.
     *
     * <p>Each attempt re-reads the trip, validates the transition against the
     * state it actually observed, and only then attempts the conditional
     * UPDATE. Losing that UPDATE means another writer moved the trip first, so
     * the attempt is retried against the new state instead of blindly
     * overwriting it. After {@link #MAX_STATUS_ATTEMPTS} losses the caller is
     * told to retry rather than being handed a status nobody committed.
     */
    @Transactional
    public Trip updateStatus(Long tripId, UpdateTripStatusRequest request, String actor) {
        TripStatus target = request.status();

        for (int attempt = 1; attempt <= MAX_STATUS_ATTEMPTS; attempt++) {
            Trip current = findById(tripId);
            TripStatus from = current.getStatus();
            String requestedDriver = TripRequestNormalizer.normalizeOptional(request.driverName());
            boolean statusUnchanged = from == target;
            boolean driverChanges = requestedDriver != null && !requestedDriver.equals(current.getDriverName());

            // Re-sending the state a trip is already in changes nothing. Treating
            // that as success rather than an error is what lets a client retry a
            // PATCH safely, including one that lost a concurrent race.
            if (statusUnchanged && !driverChanges) {
                return current;
            }
            if (!statusUnchanged && !from.canTransitionTo(target)) {
                throw new InvalidStatusTransitionException(tripId, from, target);
            }

            String driverName = requestedDriver != null ? requestedDriver : current.getDriverName();
            if (target.requiresDriver() && TripRequestNormalizer.isBlank(driverName)) {
                throw new DriverRequiredException(tripId, target);
            }

            Instant now = Instant.now(clock);
            int rowsUpdated = trips.updateStatus(tripId, from, current.getVersion(), target, driverName, now);

            if (rowsUpdated == 1) {
                Trip updated = findById(tripId);
                history.save(new TripStatusHistory(tripId, from, target, actor, updated.getUpdatedAt()));
                return updated;
            }

            log.debug("Status update for trip {} lost a race on attempt {} (from {})",
                    tripId, attempt, from);
        }

        throw new ConcurrentUpdateException(tripId, MAX_STATUS_ATTEMPTS);
    }

    // ------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Trip findById(Long tripId) {
        return trips.findById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
    }

    @Transactional(readOnly = true)
    public Page<Trip> list(TripFilter filter, Pageable pageable) {
        return trips.findAll(TripSpecifications.fromFilter(filter), PageRequests.clamp(pageable));
    }

    @Transactional(readOnly = true)
    public List<TripStatusHistory> statusHistory(Long tripId) {
        findById(tripId);
        return history.findByTripIdOrderByChangedAtDescIdDesc(tripId);
    }

    // ------------------------------------------------------------------ delete

    /**
     * Deletes a trip and, through the cascading foreign key, its status history.
     *
     * <p>Deletion is allowed in any status. A stricter system would refuse to
     * delete a trip that is IN_TRANSIT; see the README for that follow up.
     */
    @Transactional
    public void delete(Long tripId) {
        if (!trips.existsById(tripId)) {
            throw new TripNotFoundException(tripId);
        }
        trips.deleteById(tripId);
    }
}
