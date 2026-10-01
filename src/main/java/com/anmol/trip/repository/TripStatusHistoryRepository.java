package com.anmol.trip.repository;

import com.anmol.trip.domain.TripStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripStatusHistoryRepository extends JpaRepository<TripStatusHistory, Long> {

    List<TripStatusHistory> findByTripIdOrderByChangedAtDescIdDesc(Long tripId);
}
