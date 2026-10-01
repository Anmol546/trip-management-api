package com.anmol.trip.repository;

import com.anmol.trip.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByIdempotencyKey(String idempotencyKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update IdempotencyKey k set k.tripId = :tripId where k.idempotencyKey = :idempotencyKey")
    int attachTrip(@Param("idempotencyKey") String idempotencyKey, @Param("tripId") Long tripId);

    long deleteByIdempotencyKey(String idempotencyKey);
}
