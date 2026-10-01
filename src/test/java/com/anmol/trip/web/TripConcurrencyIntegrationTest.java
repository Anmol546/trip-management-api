package com.anmol.trip.web;

import com.anmol.trip.domain.TripStatus;
import com.anmol.trip.repository.IdempotencyKeyRepository;
import com.anmol.trip.repository.TripRepository;
import com.anmol.trip.repository.TripStatusHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the concurrency claims rather than asserting them in prose.
 *
 * <p>Each test fires genuinely simultaneous HTTP requests through the full
 * filter chain, so the conditional UPDATE, the transaction boundary and the
 * audit trail are all exercised together.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripConcurrencyIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripStatusHistoryRepository historyRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        // History rows go with the trips through the cascading foreign key.
        tripRepository.deleteAll();
        idempotencyKeys.deleteAll();
    }

    @Test
    @DisplayName("simultaneous identical transitions all report success and apply exactly once")
    void concurrentIdenticalTransitionsAreAppliedOnce() throws Exception {
        Long tripId = createTrip("TRIP-CONCURRENT");

        List<Integer> statuses = fireConcurrently(repeated(THREADS,
                () -> patchStatus(tripId, "ASSIGNED", "Ravi Kumar")));

        assertThat(statuses).containsOnly(200);
        assertThat(tripRepository.findById(tripId).orElseThrow().getStatus())
                .isEqualTo(TripStatus.ASSIGNED);

        List<String> recorded = historyRepository.findByTripIdOrderByChangedAtDescIdDesc(tripId)
                .stream()
                .map(entry -> entry.getFromStatus() + "->" + entry.getToStatus())
                .toList();

        // The creation entry plus exactly one CREATED->ASSIGNED. Losing the race
        // is not a failure: the loser re-reads, finds the trip already ASSIGNED
        // and reports success without writing a second transition.
        assertThat(recorded).containsExactlyInAnyOrder("null->CREATED", "CREATED->ASSIGNED");
    }

    @Test
    @DisplayName("simultaneous competing targets leave exactly one recorded move")
    void concurrentCompetingTransitionsLeaveOneWinner() throws Exception {
        Long tripId = createTrip("TRIP-RACE");

        // Half the racers try to assign the trip, half try to cancel it. Both are
        // legal from CREATED, so exactly one of them must win.
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS / 2; i++) {
            attempts.add(() -> patchStatus(tripId, "ASSIGNED", "Ravi Kumar"));
        }
        for (int i = 0; i < THREADS / 2; i++) {
            attempts.add(() -> patchStatus(tripId, "CANCELLED", null));
        }

        List<Integer> statuses = fireConcurrently(attempts);

        assertThat(statuses).allMatch(code -> code == 200 || code == 409);
        TripStatus finalStatus = tripRepository.findById(tripId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(TripStatus.ASSIGNED, TripStatus.CANCELLED);

        long appliedMoves = historyRepository.findByTripIdOrderByChangedAtDescIdDesc(tripId).stream()
                .filter(entry -> entry.getFromStatus() == TripStatus.CREATED)
                .count();
        assertThat(appliedMoves).isEqualTo(1);
    }

    @Test
    @DisplayName("a trip that reaches a terminal state under contention stays there")
    void concurrentWritesCannotLeaveAnInconsistentState() throws Exception {
        Long tripId = createTrip("TRIP-CLOSED");
        patchStatus(tripId, "ASSIGNED", "Ravi Kumar");
        patchStatus(tripId, "IN_TRANSIT", null);

        List<Callable<Integer>> attempts = new ArrayList<>();
        attempts.add(() -> patchStatus(tripId, "COMPLETED", null));
        for (int i = 0; i < THREADS - 1; i++) {
            attempts.add(() -> patchStatus(tripId, "CANCELLED", null));
        }

        List<Integer> statuses = fireConcurrently(attempts);

        // Whatever the interleaving, the trip settles on exactly one terminal
        // status and never records a move out of it.
        TripStatus finalStatus = tripRepository.findById(tripId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(TripStatus.COMPLETED, TripStatus.CANCELLED);

        List<String> recorded = historyRepository.findByTripIdOrderByChangedAtDescIdDesc(tripId)
                .stream()
                .map(entry -> entry.getFromStatus() + "->" + entry.getToStatus())
                .toList();
        assertThat(recorded).contains("IN_TRANSIT->" + finalStatus);
        assertThat(recorded).doesNotContain("COMPLETED->CANCELLED", "CANCELLED->COMPLETED",
                "COMPLETED->IN_TRANSIT", "CANCELLED->ASSIGNED");

        // The losers were told they lost; none of them produced a server error.
        assertThat(statuses).allMatch(code -> code == 200 || code == 409);
    }

    @Test
    @DisplayName("an illegal transition racing eight times is rejected every time")
    void illegalTransitionsAreRejectedUnderContention() throws Exception {
        Long tripId = createTrip("TRIP-ILLEGAL");

        List<Integer> statuses = fireConcurrently(repeated(THREADS,
                () -> patchStatus(tripId, "COMPLETED", "Ravi Kumar")));

        assertThat(statuses).containsOnly(409);
        assertThat(tripRepository.findById(tripId).orElseThrow().getStatus())
                .isEqualTo(TripStatus.CREATED);
    }

    @Test
    @DisplayName("concurrent creates sharing an Idempotency-Key collapse into one trip")
    void concurrentCreatesWithTheSameIdempotencyKeyCollapse() throws Exception {
        String body = """
                {"tripNumber":"TRIP-IDEM","source":"Delhi NCR","destination":"Jaipur"}""";

        List<Integer> statuses = fireConcurrently(repeated(THREADS,
                () -> createWithKey("key-shared", body)));

        // The invariant that matters: a burst of identical creates produces exactly
        // one trip. Whether the losers are told "here is your trip" (200) or
        // "still working on it" (409) depends on timing and both are safe.
        assertThat(statuses).allMatch(code -> code == 200 || code == 201 || code == 409);
        assertThat(statuses.stream().filter(code -> code == 201).count()).isOne();
        assertThat(tripRepository.count()).isOne();
    }

    @Test
    @DisplayName("concurrent creates without an idempotency key still cannot duplicate the trip number")
    void concurrentCreatesWithoutAKeyCannotDuplicate() throws Exception {
        String body = """
                {"tripNumber":"TRIP-RACE-CREATE","source":"Delhi NCR","destination":"Jaipur"}""";

        List<Integer> statuses = fireConcurrently(repeated(THREADS, () -> createWithoutKey(body)));

        assertThat(statuses.stream().filter(code -> code == 201).count()).isOne();
        assertThat(statuses.stream().filter(code -> code == 409).count()).isEqualTo(THREADS - 1);
        assertThat(tripRepository.count()).isOne();
    }

    // ----------------------------------------------------------------- helpers

    private List<Callable<Integer>> repeated(int times, Callable<Integer> call) {
        List<Callable<Integer>> calls = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            calls.add(call);
        }
        return calls;
    }

    private Long createTrip(String tripNumber) throws Exception {
        String body = """
                {"tripNumber":"%s","source":"Delhi NCR","destination":"Jaipur"}""".formatted(tripNumber);
        MvcResult result = mockMvc.perform(post("/api/v1/trips")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private int createWithKey(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/trips")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private int createWithoutKey(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/trips")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private int patchStatus(Long tripId, String status, String driverName) throws Exception {
        String payload = driverName == null
                ? "{\"status\":\"%s\"}".formatted(status)
                : "{\"status\":\"%s\",\"driverName\":\"%s\"}".formatted(status, driverName);
        return mockMvc.perform(patch("/api/v1/trips/{id}/status", tripId)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn().getResponse().getStatus();
    }

    /**
     * Runs the supplied batch with every thread released by the same latch, so the
     * requests genuinely overlap instead of being serialised by the pool.
     */
    private List<Integer> fireConcurrently(List<Callable<Integer>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
