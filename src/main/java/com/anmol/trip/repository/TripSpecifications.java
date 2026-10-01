package com.anmol.trip.repository;

import com.anmol.trip.domain.Trip;
import com.anmol.trip.domain.TripStatus;
import com.anmol.trip.web.dto.TripFilter;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Composable query predicates for the trip listing. Kept out of the controller
 * so that adding a filter is a one line change here plus one query parameter.
 */
public final class TripSpecifications {

    private TripSpecifications() {
    }

    public static Specification<Trip> fromFilter(TripFilter filter) {
        List<Specification<Trip>> predicates = new ArrayList<>();

        if (filter.status() != null) {
            predicates.add(statusIn(List.of(filter.status())));
        }
        addIfPresent(predicates, filter.source(), TripSpecifications::sourceEqualsIgnoreCase);
        addIfPresent(predicates, filter.destination(), TripSpecifications::destinationEqualsIgnoreCase);
        addIfPresent(predicates, filter.tripNumber(), TripSpecifications::tripNumberEqualsIgnoreCase);

        if (filter.createdFrom() != null) {
            predicates.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            predicates.add((root, query, cb) -> cb.lessThan(root.get("createdAt"), filter.createdTo()));
        }

        if (predicates.isEmpty()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return predicates.stream().reduce((left, right) -> left.and(right)).orElseThrow();
    }

    public static Specification<Trip> statusIn(Collection<TripStatus> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    private static Specification<Trip> sourceEqualsIgnoreCase(String value) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("source")), value.toLowerCase());
    }

    private static Specification<Trip> destinationEqualsIgnoreCase(String value) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("destination")), value.toLowerCase());
    }

    private static Specification<Trip> tripNumberEqualsIgnoreCase(String value) {
        return (root, query, cb) -> cb.equal(cb.upper(root.get("tripNumber")), value.toUpperCase());
    }

    private static void addIfPresent(List<Specification<Trip>> target, String value,
                                     java.util.function.Function<String, Specification<Trip>> factory) {
        if (value != null && !value.isBlank()) {
            target.add(factory.apply(value.trim()));
        }
    }
}
