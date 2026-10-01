package com.anmol.trip.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle state of a trip.
 *
 * <p>The legal forward transitions are declared here so that the rule lives in one
 * place and cannot drift between the API layer and the service layer:
 *
 * <pre>
 *   CREATED --&gt; ASSIGNED --&gt; IN_TRANSIT --&gt; COMPLETED
 *      |            |             |
 *      +------------+-------------+--&gt; CANCELLED
 * </pre>
 *
 * <p>{@code COMPLETED} and {@code CANCELLED} are terminal: they have no outgoing
 * transitions. Backwards moves are therefore impossible by construction. A
 * transition to the status a trip is already in is not modelled as an edge; the
 * service treats it as a no-op so that client retries remain safe.
 */
public enum TripStatus {

    CREATED,
    ASSIGNED,
    IN_TRANSIT,
    COMPLETED,
    CANCELLED;

    private static final Map<TripStatus, Set<TripStatus>> TRANSITIONS = buildTransitions();

    private static Map<TripStatus, Set<TripStatus>> buildTransitions() {
        Map<TripStatus, Set<TripStatus>> edges = new EnumMap<>(TripStatus.class);
        edges.put(CREATED, immutableSet(ASSIGNED, CANCELLED));
        edges.put(ASSIGNED, immutableSet(IN_TRANSIT, CANCELLED));
        edges.put(IN_TRANSIT, immutableSet(COMPLETED, CANCELLED));
        edges.put(COMPLETED, immutableSet());
        edges.put(CANCELLED, immutableSet());
        return Collections.unmodifiableMap(edges);
    }

    @SafeVarargs
    private static Set<TripStatus> immutableSet(TripStatus... values) {
        if (values.length == 0) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(java.util.Arrays.asList(values)));
    }

    /** Statuses this status is allowed to move to. Empty for terminal statuses. */
    public Set<TripStatus> allowedTargets() {
        return TRANSITIONS.get(this);
    }

    public boolean canTransitionTo(TripStatus target) {
        return target != null && allowedTargets().contains(target);
    }

    /** A trip can only be assigned, started or completed once a driver is known. */
    public boolean requiresDriver() {
        return this == ASSIGNED || this == IN_TRANSIT || this == COMPLETED;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
