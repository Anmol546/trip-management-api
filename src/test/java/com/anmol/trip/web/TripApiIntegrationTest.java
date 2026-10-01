package com.anmol.trip.web;

import com.anmol.trip.repository.IdempotencyKeyRepository;
import com.anmol.trip.repository.TripRepository;
import com.anmol.trip.web.dto.CreateTripRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private com.anmol.trip.repository.IdempotencyKeyRepository idempotencyKeys;

    @BeforeEach
    void clean() {
        // History rows go with the trips through the cascading foreign key.
        tripRepository.deleteAll();
        idempotencyKeys.deleteAll();
    }

    // ------------------------------------------------------------------ create

    @Nested
    @DisplayName("POST /api/v1/trips")
    class Create {

        @Test
        void createsATripInCreatedStatus() throws Exception {
            mockMvc.perform(post("/api/v1/trips")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("TRIP-1", "Delhi NCR", "Jaipur", null)))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.tripNumber").value("TRIP-1"))
                    .andExpect(jsonPath("$.source").value("Delhi NCR"))
                    .andExpect(jsonPath("$.destination").value("Jaipur"))
                    .andExpect(jsonPath("$.status").value("CREATED"))
                    .andExpect(jsonPath("$.driverName").doesNotExist())
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.updatedAt").exists());

            assertThat(tripRepository.count()).isOne();
        }

        @Test
        void acceptsATripNumberWithMixedCaseAndPaddingAndStoresItCanonical() throws Exception {
            mockMvc.perform(post("/api/v1/trips")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("  trip-42  ", "Delhi NCR", "Jaipur", null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tripNumber").value("TRIP-42"));

            assertThat(tripRepository.findAll().getFirst().getTripNumber()).isEqualTo("TRIP-42");
        }

        @Test
        void rejectsADuplicateTripNumberRegardlessOfCase() throws Exception {
            createTrip("TRIP-1");

            mockMvc.perform(post("/api/v1/trips")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("trip-1", "Mumbai", "Pune", null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_NUMBER_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.details.existingTripId").exists());

            assertThat(tripRepository.count()).isOne();
        }

        @Test
        void rejectsABlankTripNumber() throws Exception {
            mockMvc.perform(post("/api/v1/trips")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("   ", "Delhi NCR", "Jaipur", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.violations[0].field").value("tripNumber"));

            assertThat(tripRepository.count()).isZero();
        }

        @Test
        void reportsEveryInvalidFieldAtOnce() throws Exception {
            mockMvc.perform(post("/api/v1/trips")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations.length()").value(3));
        }

        @Test
        void rejectsAnOverlongTripNumber() throws Exception {
            mockMvc.perform(post("/api/v1/trips")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("X".repeat(51), "Delhi NCR", "Jaipur", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        void rejectsAnUnknownStatusValue() throws Exception {
            Long id = createTrip("TRIP-BADSTATUS");

            mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"TELEPORTED\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        void alsoServesTheUnversionedPath() throws Exception {
            mockMvc.perform(post("/trips")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("TRIP-9", "Delhi NCR", "Jaipur", null)))
                    .andExpect(status().isCreated());
        }
    }

    // -------------------------------------------------------------- idempotency

    @Nested
    @DisplayName("POST /api/v1/trips with Idempotency-Key")
    class Idempotency {

        @Test
        void replayingAKeyReturnsTheOriginalTrip() throws Exception {
            String body = json("TRIP-1", "Delhi NCR", "Jaipur", null);

            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "key-abc").content(body))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "key-abc").content(body))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotency-Replayed", "true"))
                    .andExpect(jsonPath("$.tripNumber").value("TRIP-1"));

            assertThat(tripRepository.count()).isOne();
        }

        @Test
        void reusingAKeyWithADifferentBodyIsAConflict() throws Exception {
            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "key-abc")
                            .content(json("TRIP-1", "Delhi NCR", "Jaipur", null)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "key-abc")
                            .content(json("TRIP-2", "Mumbai", "Pune", null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

            assertThat(tripRepository.count()).isOne();
        }

        @Test
        void differentKeysCreateDifferentTrips() throws Exception {
            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "key-1").content(json("TRIP-1", "A", "B", null)))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "key-2").content(json("TRIP-2", "A", "B", null)))
                    .andExpect(status().isCreated());

            assertThat(tripRepository.count()).isEqualTo(2);
        }

        @Test
        void aFailedCreateReleasesTheKeySoTheClientCanRetry() throws Exception {
            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "key-abc").content(json("TRIP-1", "A", "B", null)))
                    .andExpect(status().isCreated());

            // Same trip number, new key: fails on the unique index, and the claim
            // for the new key must not survive the failure.
            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "key-def")
                    .content(json("TRIP-1", "A", "B", null)))
                    .andExpect(status().isConflict());

            // key-def is free again, so it can be used for a genuinely new trip.
            mockMvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "key-def").content(json("TRIP-77", "A", "B", null)))
                    .andExpect(status().isCreated());

            assertThat(tripRepository.count()).isEqualTo(2);
        }
    }

    // -------------------------------------------------------------------- read

    @Nested
    @DisplayName("GET /api/v1/trips")
    class Read {

        @Test
        void returnsAnEmptyFirstPageWhenNothingExists() throws Exception {
            mockMvc.perform(get("/api/v1/trips"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.empty").value(true));
        }

        @Test
        void paginatesWithNewestFirst() throws Exception {
            for (int i = 1; i <= 5; i++) {
                createTrip("TRIP-" + i);
            }

            mockMvc.perform(get("/api/v1/trips").param("size", "2").param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.first").value(true))
                    .andExpect(jsonPath("$.last").value(false));

            mockMvc.perform(get("/api/v1/trips").param("size", "2").param("page", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.last").value(true));
        }

        @Test
        void capsThePageSize() throws Exception {
            for (int i = 1; i <= 120; i++) {
                createTrip("TRIP-" + i);
            }

            mockMvc.perform(get("/api/v1/trips").param("size", "5000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(100))
                    .andExpect(jsonPath("$.content.length()").value(100))
                    .andExpect(jsonPath("$.totalElements").value(120));
        }

        @Test
        void rejectsAnUnknownSortProperty() throws Exception {
            mockMvc.perform(get("/api/v1/trips").param("sort", "nonsense,asc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }

@Test
        void filtersByStatus() throws Exception {
            Long created = createTrip("TRIP-1");
            createTrip("TRIP-2");
            transition(created, "ASSIGNED", "Ravi Kumar");

            mockMvc.perform(get("/api/v1/trips").param("status", "ASSIGNED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].tripNumber").value("TRIP-1"));

            mockMvc.perform(get("/api/v1/trips").param("status", "CREATED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].tripNumber").value("TRIP-2"));

            mockMvc.perform(get("/api/v1/trips").param("status", "COMPLETED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        void rejectsAnUnknownStatusFilter() throws Exception {
            mockMvc.perform(get("/api/v1/trips").param("status", "NOPE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }

        @Test
        void filtersBySourceAndDestinationCaseInsensitively() throws Exception {
            createTrip("TRIP-1", "Delhi NCR", "Jaipur");
            createTrip("TRIP-2", "Delhi NCR", "Mumbai");

            mockMvc.perform(get("/api/v1/trips").param("source", "delhi ncr"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));
            mockMvc.perform(get("/api/v1/trips").param("source", "Delhi NCR").param("destination", "Mumbai"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].tripNumber").value("TRIP-2"));
        }

        @Test
        void sortsOnAClientSuppliedProperty() throws Exception {
            createTrip("TRIP-B");
            createTrip("TRIP-A");

            mockMvc.perform(get("/api/v1/trips").param("sort", "tripNumber,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].tripNumber").value("TRIP-A"))
                    .andExpect(jsonPath("$.content[1].tripNumber").value("TRIP-B"));
        }
    }

    @Test
    void getByIdReturnsTheTrip() throws Exception {
        Long id = createTrip("TRIP-1");

        mockMvc.perform(get("/api/v1/trips/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.tripNumber").value("TRIP-1"));
    }

    @Test
    void getByIdOnAnUnknownTripIs404WithACode() throws Exception {
        mockMvc.perform(get("/api/v1/trips/{id}", 987654))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deleteRemovesTheTripAndItsHistory() throws Exception {
        Long id = createTrip("TRIP-1");
        transition(id, "ASSIGNED", "Ravi Kumar");

        mockMvc.perform(get("/api/v1/trips/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(delete("/api/v1/trips/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/trips/{id}", id))
                .andExpect(status().isNotFound());
        assertThat(tripRepository.count()).isZero();
    }

    @Test
    void deletingAnUnknownTripIs404() throws Exception {
        mockMvc.perform(delete("/api/v1/trips/{id}", 987654))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }

    // -------------------------------------------------------- status lifecycle

    @Nested
    @DisplayName("PATCH /api/v1/trips/{id}/status")
    class Status {

        @Test
        void walksTheHappyPathAndRecordsHistory() throws Exception {
            Long id = createTrip("TRIP-1");

            transition(id, "ASSIGNED", "Ravi Kumar");
            transition(id, "IN_TRANSIT", null);
            transition(id, "COMPLETED", null);

            mockMvc.perform(get("/api/v1/trips/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.version").value(3));

            mockMvc.perform(get("/api/v1/trips/{id}/history", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(4))
                    .andExpect(jsonPath("$[0].toStatus").value("COMPLETED"))
                    .andExpect(jsonPath("$[0].fromStatus").value("IN_TRANSIT"))
                    .andExpect(jsonPath("$[3].toStatus").value("CREATED"))
                    .andExpect(jsonPath("$[3].fromStatus").doesNotExist());
        }

        @Test
        void refusesToMoveBackwards() throws Exception {
            Long id = createTrip("TRIP-1");
            transition(id, "ASSIGNED", "Ravi Kumar");
            transition(id, "IN_TRANSIT", null);

            mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ASSIGNED\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.details.currentStatus").value("IN_TRANSIT"))
                    .andExpect(jsonPath("$.details.requestedStatus").value("ASSIGNED"));

            mockMvc.perform(get("/api/v1/trips/{id}", id))
                    .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        }

        @Test
        void refusesToSkipForward() throws Exception {
            Long id = createTrip("TRIP-1");

            mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"IN_TRANSIT\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        }

        @Test
        void refusesToLeaveATerminalStatus() throws Exception {
            Long id = createTrip("TRIP-1");
            transition(id, "ASSIGNED", "Ravi Kumar");
            transition(id, "IN_TRANSIT", null);
            transition(id, "COMPLETED", null);

            mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"IN_TRANSIT\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.details.allowedTransitions").value("none, this status is terminal"));
        }

        @Test
        void cancelsFromAnyActiveStatus() throws Exception {
            Long fromCreated = createTrip("TRIP-1");
            transition(fromCreated, "CANCELLED", null);
            mockMvc.perform(get("/api/v1/trips/{id}", fromCreated))
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            Long fromAssigned = createTrip("TRIP-2");
            transition(fromAssigned, "ASSIGNED", "Ravi Kumar");
            transition(fromAssigned, "CANCELLED", null);
            mockMvc.perform(get("/api/v1/trips/{id}", fromAssigned))
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            Long fromInTransit = createTrip("TRIP-3");
            transition(fromInTransit, "ASSIGNED", "Ravi Kumar");
            transition(fromInTransit, "IN_TRANSIT", null);
            transition(fromInTransit, "CANCELLED", null);
            mockMvc.perform(get("/api/v1/trips/{id}", fromInTransit))
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        void requiresADriverBeforeAssigning() throws Exception {
            Long id = createTrip("TRIP-1");

            mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ASSIGNED\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DRIVER_REQUIRED"));
        }

        @Test
        void cancellingDoesNotRequireADriver() throws Exception {
            Long id = createTrip("TRIP-1");
            transition(id, "CANCELLED", null);

            mockMvc.perform(get("/api/v1/trips/{id}", id))
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        void driverSuppliedAtCreationIsCarriedForward() throws Exception {
            Long id = createTripWithDriver("TRIP-1", "Ravi Kumar");

            transition(id, "ASSIGNED", null);

            mockMvc.perform(get("/api/v1/trips/{id}", id))
                    .andExpect(jsonPath("$.status").value("ASSIGNED"))
                    .andExpect(jsonPath("$.driverName").value("Ravi Kumar"));
        }

        @Test
        void aStatusThatIsAlreadySetIsANoOpSoRetriesAreSafe() throws Exception {
            Long id = createTrip("TRIP-1");
            transition(id, "ASSIGNED", "Ravi Kumar");

            mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ASSIGNED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ASSIGNED"));

            mockMvc.perform(get("/api/v1/trips/{id}/history", id))
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        void theDriverCanBeReassignedWhileTheTripStaysAssigned() throws Exception {
            Long id = createTrip("TRIP-1");
            transition(id, "ASSIGNED", "Ravi Kumar");

            mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ASSIGNED\",\"driverName\":\"Asha Menon\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.driverName").value("Asha Menon"));
        }

        @Test
        void rejectsAMissingStatus() throws Exception {
            Long id = createTrip("TRIP-1");

            mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        void patchingAnUnknownTripIs404() throws Exception {
            mockMvc.perform(patch("/api/v1/trips/{id}/status", 987654)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ASSIGNED\",\"driverName\":\"Ravi\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }
    }

    // ----------------------------------------------------------------- helpers

    private Long createTrip(String tripNumber) throws Exception {
        return createTripWithDriver(tripNumber, null);
    }

    private Long createTrip(String tripNumber, String source, String destination) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(tripNumber, source, destination, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long createTripWithDriver(String tripNumber, String driverName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(tripNumber, "Delhi NCR", "Jaipur", driverName)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void transition(Long id, String status, String driverName) throws Exception {
        String payload = driverName == null
                ? "{\"status\":\"%s\"}".formatted(status)
                : "{\"status\":\"%s\",\"driverName\":\"%s\"}".formatted(status, driverName);
        mockMvc.perform(patch("/api/v1/trips/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
    }

    private static String json(String tripNumber, String source, String destination, String driverName) {
        String driver = driverName == null ? "" : ",\"driverName\":\"" + driverName + "\"";
        return "{\"tripNumber\":\"%s\",\"source\":\"%s\",\"destination\":\"%s\"%s}"
                .formatted(tripNumber, source, destination, driver);
    }
}
