package com.anmol.trip.repository;

import com.anmol.trip.domain.Trip;
import com.anmol.trip.domain.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long>, JpaSpecificationExecutor<Trip> {

    Optional<Trip> findByTripNumberIgnoreCase(String tripNumber);

    /**
     * Compare-and-set status change.
     *
     * <p>This is the whole concurrency story for {@code PATCH /trips/{id}/status}.
     * The statement only succeeds when the trip is still in the status the caller
     * observed <em>and</em> still carries the version the caller observed. If a
     * concurrent request has already moved the trip on, the WHERE clause matches
     * zero rows and this method returns 0, and the service retries against fresh
     * state or reports a conflict. No row lock is held for longer than the single
     * statement, so a burst of updates on the same trip serialises cheaply instead
     * of blocking a transaction for its whole duration.
     *
     * <p>Bulk JPQL updates bypass the JPA {@code @Version} counter, so the version
     * is incremented explicitly here to keep the column authoritative.
     *
     * @return 1 when the transition was applied, 0 when another writer won the race
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Trip t
               set t.status = :toStatus,
                   t.driverName = coalesce(:driverName, t.driverName),
                   t.version = t.version + 1,
                   t.updatedAt = :now
             where t.id = :id
               and t.status = :expectedStatus
               and t.version = :expectedVersion
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("expectedStatus") TripStatus expectedStatus,
                     @Param("expectedVersion") Long expectedVersion,
                     @Param("toStatus") TripStatus toStatus,
                     @Param("driverName") String driverName,
                     @Param("now") Instant now);
}
