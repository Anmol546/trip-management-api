package com.anmol.trip.service;

import com.anmol.trip.domain.IdempotencyKey;
import com.anmol.trip.exception.IdempotencyKeyConflictException;
import com.anmol.trip.exception.IdempotentRequestInProgressException;
import com.anmol.trip.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Makes {@code POST /trips} safe to retry.
 *
 * <p>The flow is claim / complete / release, and each step runs in its own
 * transaction so that the claim becomes visible to concurrent duplicates before
 * the trip itself exists:
 *
 * <ol>
 *   <li><b>claim</b> inserts a row keyed by the client supplied
 *       {@code Idempotency-Key}. The unique index makes exactly one concurrent
 *       caller win; the others read the winner's row.</li>
 *   <li>the caller creates the trip in its own transaction.</li>
 *   <li><b>complete</b> stores the new trip id on the claim.</li>
 *   <li><b>release</b> deletes the claim if anything went wrong, so the client
 *       is free to retry the same key.</li>
 * </ol>
 *
 * <p>Every step stores a fingerprint of the request body. Replaying a key with a
 * different payload is rejected rather than silently returning the first trip,
 * which would hide a client bug.
 *
 * <p>The steps are driven by an explicit {@link TransactionTemplate} rather than
 * {@code @Transactional} because they are invoked from within this same bean,
 * where the transactional proxy would not apply.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** A claim older than this is assumed to belong to a crashed request and is reclaimed. */
    static final Duration CLAIM_TTL = Duration.ofMinutes(10);

    /** How long a duplicate request waits for the original to finish before giving up. */
    static final Duration MAX_WAIT = Duration.ofSeconds(2);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final IdempotencyKeyRepository repository;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyKeyRepository repository, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Result of trying to take ownership of an idempotency key.
     *
     * @param claimed {@code true} when this caller owns the key and must perform
     *                the create; {@code false} when the request is a duplicate
     * @param tripId  id of the trip produced by the original request, or
     *                {@code null} while that request is still running
     */
    public record Claim(String idempotencyKey, boolean claimed, Long tripId) {
    }

    private enum Outcome {
        CLAIMED, REPLAY, IN_PROGRESS, RACE_LOST
    }

    public Claim claim(String key, String fingerprint) {
        Outcome outcome = inNewTransaction(status -> insertOrRead(status, key, fingerprint));

        if (outcome == Outcome.CLAIMED) {
            return new Claim(key, true, null);
        }

        // REPLAY, IN_PROGRESS and RACE_LOST all mean another request owns the key.
        // They differ only in how the claim got there, and in all three cases the
        // right move is to wait for that request to publish its trip rather than to
        // fail immediately, because most duplicates arrive in the same burst as the
        // original.
        return awaitResolution(key, fingerprint);
    }

    public void complete(String key, Long tripId) {
        inNewTransaction(status -> {
            repository.attachTrip(key, tripId);
            return null;
        });
    }

    public void release(String key) {
        inNewTransaction(status -> {
            long removed = repository.deleteByIdempotencyKey(key);
            if (removed == 0) {
                log.debug("Idempotency claim {} was already gone, nothing to release", key);
            }
            return null;
        });
    }

    /**
     * Insert the claim, or report what an existing claim means. Isolated in its
     * own transaction because a failed flush poisons it, and both the fallback
     * read and the rollback have to happen outside it.
     */
    private Outcome insertOrRead(TransactionStatus transactionStatus, String key, String fingerprint) {
        Optional<IdempotencyKey> existing = readValidRow(key);
        if (existing.isPresent()) {
            IdempotencyKey row = existing.get();
            requireMatchingFingerprint(key, row, fingerprint);
            return row.getTripId() != null ? Outcome.REPLAY : Outcome.IN_PROGRESS;
        }

        try {
            repository.saveAndFlush(new IdempotencyKey(key, fingerprint, Instant.now(clock)));
            return Outcome.CLAIMED;
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            // Either another transaction got the unique key first, or we could not
            // wait for it long enough. Both mean "somebody else owns this key", so
            // the correct next move is to read their row rather than to fail.
            log.debug("Lost the race to claim idempotency key {}: {}", key, e.getClass().getSimpleName());
            // The failed flush has already marked this transaction rollback-only.
            // Roll it back on purpose, otherwise the commit fails with
            // UnexpectedRollbackException instead of returning RACE_LOST.
            transactionStatus.setRollbackOnly();
            return Outcome.RACE_LOST;
        }
    }

    /**
     * Waits for the in-flight original request to record its trip id, so an
     * impatient retry still gets the trip back instead of an error.
     */
    private Claim awaitResolution(String key, String fingerprint) {
        Instant deadline = Instant.now(clock).plus(MAX_WAIT);
        while (true) {
            Claim resolved = inNewTransaction(status -> readResolution(key, fingerprint));
            if (resolved != null) {
                return resolved;
            }
            if (!Instant.now(clock).isBefore(deadline)) {
                throw new IdempotentRequestInProgressException(key);
            }
            sleep(key);
        }
    }

    /**
     * @return a {@link Claim} once the original request has produced its trip, or
     *         {@code null} while it is still running or has released the claim
     */
    private Claim readResolution(String key, String fingerprint) {
        Optional<IdempotencyKey> existing = readValidRow(key);
        if (existing.isEmpty()) {
            // The original request failed and released the key. Nothing to replay.
            return null;
        }
        IdempotencyKey row = existing.get();
        requireMatchingFingerprint(key, row, fingerprint);
        return row.getTripId() != null ? new Claim(key, false, row.getTripId()) : null;
    }

    /**
     * Reads the claim row, deleting it first if it is older than {@link #CLAIM_TTL}
     * and still unattached. That makes the table self healing after a crash in the
     * middle of a request.
     */
    private Optional<IdempotencyKey> readValidRow(String key) {
        Optional<IdempotencyKey> row = repository.findByIdempotencyKey(key);
        if (row.isPresent() && isExpired(row.get())) {
            log.warn("Reclaiming stale idempotency claim {}", key);
            repository.delete(row.get());
            return Optional.empty();
        }
        return row;
    }

    private boolean isExpired(IdempotencyKey row) {
        return row.getTripId() == null && row.getCreatedAt().isBefore(Instant.now(clock).minus(CLAIM_TTL));
    }

    private void requireMatchingFingerprint(String key, IdempotencyKey row, String fingerprint) {
        if (!row.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException(key);
        }
    }

    private <T> T inNewTransaction(org.springframework.transaction.support.TransactionCallback<T> work) {
        return requiresNew.execute(work);
    }

    private void sleep(String key) {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdempotentRequestInProgressException(key);
        }
    }
}
