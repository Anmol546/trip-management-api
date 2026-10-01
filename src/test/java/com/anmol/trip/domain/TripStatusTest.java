package com.anmol.trip.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class TripStatusTest {

    @Nested
    @DisplayName("legal forward transitions")
    class LegalTransitions {

        @Test
        void createdMayBeAssignedOrCancelled() {
            assertThat(TripStatus.CREATED.allowedTargets())
                    .containsExactlyInAnyOrder(TripStatus.ASSIGNED, TripStatus.CANCELLED);
        }

        @Test
        void assignedMayStartOrCancel() {
            assertThat(TripStatus.ASSIGNED.allowedTargets())
                    .containsExactlyInAnyOrder(TripStatus.IN_TRANSIT, TripStatus.CANCELLED);
        }

        @Test
        void inTransitMayOnlyCompleteOrCancel() {
            assertThat(TripStatus.IN_TRANSIT.allowedTargets())
                    .containsExactlyInAnyOrder(TripStatus.COMPLETED, TripStatus.CANCELLED);
        }

        @ParameterizedTest
        @EnumSource(TripStatus.class)
        void theHappyPathIsWalkable(TripStatus from) {
            if (from.canTransitionTo(TripStatus.ASSIGNED)) {
                assertThat(TripStatus.ASSIGNED.canTransitionTo(TripStatus.IN_TRANSIT)).isTrue();
                assertThat(TripStatus.IN_TRANSIT.canTransitionTo(TripStatus.COMPLETED)).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("transitions that must be rejected")
    class RejectedTransitions {

        @ParameterizedTest
        @EnumSource(TripStatus.class)
        void noStatusCanTransitionToItself(TripStatus status) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }

        @Test
        void completedIsTerminal() {
            assertThat(TripStatus.COMPLETED.allowedTargets()).isEmpty();
            assertThat(TripStatus.COMPLETED.isTerminal()).isTrue();
            for (TripStatus target : TripStatus.values()) {
                assertThat(TripStatus.COMPLETED.canTransitionTo(target)).isFalse();
            }
        }

        @Test
        void cancelledIsTerminal() {
            assertThat(TripStatus.CANCELLED.allowedTargets()).isEmpty();
            for (TripStatus target : TripStatus.values()) {
                assertThat(TripStatus.CANCELLED.canTransitionTo(target)).isFalse();
            }
        }

        @Test
        void nothingSkipsForward() {
            assertThat(TripStatus.CREATED.canTransitionTo(TripStatus.IN_TRANSIT)).isFalse();
            assertThat(TripStatus.CREATED.canTransitionTo(TripStatus.COMPLETED)).isFalse();
            assertThat(TripStatus.ASSIGNED.canTransitionTo(TripStatus.COMPLETED)).isFalse();
        }

        @Test
        void nothingMovesBackwards() {
            assertThat(TripStatus.IN_TRANSIT.canTransitionTo(TripStatus.ASSIGNED)).isFalse();
            assertThat(TripStatus.IN_TRANSIT.canTransitionTo(TripStatus.CREATED)).isFalse();
            assertThat(TripStatus.ASSIGNED.canTransitionTo(TripStatus.CREATED)).isFalse();
        }

        @Test
        void nullIsNotAValidTarget() {
            assertThat(TripStatus.CREATED.canTransitionTo(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("driver requirement")
    class DriverRequirement {

        @Test
        void statusesThatOperateATripNeedADriver() {
            assertThat(TripStatus.CREATED.requiresDriver()).isFalse();
            assertThat(TripStatus.CANCELLED.requiresDriver()).isFalse();
            assertThat(TripStatus.ASSIGNED.requiresDriver()).isTrue();
            assertThat(TripStatus.IN_TRANSIT.requiresDriver()).isTrue();
            assertThat(TripStatus.COMPLETED.requiresDriver()).isTrue();
        }
    }
}
