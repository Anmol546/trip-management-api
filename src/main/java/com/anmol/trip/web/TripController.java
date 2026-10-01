package com.anmol.trip.web;

import com.anmol.trip.domain.Trip;
import com.anmol.trip.domain.TripStatus;
import com.anmol.trip.domain.TripStatusHistory;
import com.anmol.trip.service.PageRequests;
import com.anmol.trip.service.TripCreation;
import com.anmol.trip.service.TripService;
import com.anmol.trip.web.dto.CreateTripRequest;
import com.anmol.trip.web.dto.PageResponse;
import com.anmol.trip.web.dto.TripFilter;
import com.anmol.trip.web.dto.TripResponse;
import com.anmol.trip.web.dto.TripStatusHistoryResponse;
import com.anmol.trip.web.dto.UpdateTripStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Trip endpoints.
 *
 * <p>Mapped under both {@code /api/v1/trips} and {@code /trips}. The versioned
 * prefix is the one to use; the bare prefix exists because that is the path the
 * specification names, and supporting both avoids a breaking rename later.
 */
@RestController
@RequestMapping({"/api/v1/trips", "/trips"})
@Tag(name = "Trips", description = "Create, query, transition and delete trips")
public class TripController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ACTOR = "X-Actor";
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    @Operation(summary = "Create a trip",
            description = """
                    Creates a trip in status CREATED.

                    Trip numbers are unique and are normalised to upper case, so `trip-1` and `TRIP-1`
                    are the same trip. Send an `Idempotency-Key` header to make retries safe: a replay
                    of the same key with the same body returns the original trip with `200 OK` instead
                    of creating a second one. Reusing a key with a different body returns `409`.

                    Without a key, a repeated create with the same trip number returns `409`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Trip created"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of an earlier create"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Trip number already used, or idempotency key reused with a different body"),
            @ApiResponse(responseCode = "422", description = "Driver is required for the requested status")
    })
    public ResponseEntity<TripResponse> create(
            @Valid @RequestBody CreateTripRequest request,
            @Parameter(description = "Client generated key that makes this create safe to retry",
                    example = "3f6c1f6e-0f2a-4a3f-9a1b-8d5e2c7b4a10")
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Parameter(description = "Who is making the change, recorded in the status audit trail",
                    example = "ops-user-42")
            @RequestHeader(value = ACTOR, required = false) String actor,
            UriComponentsBuilder uriBuilder) {

        TripCreation creation = tripService.create(request, idempotencyKey, actor);
        TripResponse body = TripResponse.from(creation.trip());
        URI location = uriBuilder.path("/api/v1/trips/{id}")
                .buildAndExpand(creation.trip().getId())
                .toUri();

        if (creation.replayed()) {
            return ResponseEntity.ok().location(location).header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping
    @Operation(summary = "List trips",
            description = "Paginated and filterable listing, most recently created first. Page size is capped at 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of trips"),
            @ApiResponse(responseCode = "400", description = "Invalid filter or pagination parameter")
    })
    public ResponseEntity<PageResponse<TripResponse>> list(
            @Parameter(description = "Only trips in this status")
            @RequestParam(required = false) TripStatus status,
            @Parameter(description = "Case-insensitive exact match on source")
            @RequestParam(required = false) String source,
            @Parameter(description = "Case-insensitive exact match on destination")
            @RequestParam(required = false) String destination,
            @Parameter(description = "Case-insensitive exact match on trip number")
            @RequestParam(required = false) String tripNumber,
            @Parameter(description = "Inclusive lower bound on createdAt")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @Parameter(description = "Exclusive upper bound on createdAt")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @PageableDefault(size = PageRequests.DEFAULT_PAGE_SIZE, sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {

        TripFilter filter = new TripFilter(status, source, destination, tripNumber, createdFrom, createdTo);
        PageResponse<TripResponse> body = PageResponse.of(
                tripService.list(filter, pageable).map(TripResponse::from));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a trip by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The trip"),
            @ApiResponse(responseCode = "404", description = "No trip with that id")
    })
    public ResponseEntity<TripResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(TripResponse.from(tripService.findById(id)));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Get the status audit trail for a trip",
            description = "Most recent transition first. The oldest entry is the implicit move into CREATED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transitions, newest first"),
            @ApiResponse(responseCode = "404", description = "No trip with that id")
    })
    public ResponseEntity<List<TripStatusHistoryResponse>> history(@PathVariable Long id) {
        List<TripStatusHistoryResponse> body = tripService.statusHistory(id).stream()
                .map(TripStatusHistoryResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update trip status",
            description = """
                    Applies a lifecycle transition. Legal moves are
                    `CREATED -> ASSIGNED -> IN_TRANSIT -> COMPLETED`, with `CANCELLED` reachable from
                    CREATED, ASSIGNED and IN_TRANSIT. COMPLETED and CANCELLED are terminal, and
                    backwards moves are rejected with `409 INVALID_STATUS_TRANSITION`.

                    A driver must be known before a trip can be ASSIGNED, either already on the trip or
                    supplied in this request.

                    Concurrent updates are safe: the transition is applied conditionally against the
                    state the server observed, so two simultaneous requests cannot both take effect.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "404", description = "No trip with that id"),
            @ApiResponse(responseCode = "409", description = "Illegal transition, or the trip is being updated concurrently"),
            @ApiResponse(responseCode = "422", description = "Driver is required for the requested status")
    })
    public ResponseEntity<TripResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTripStatusRequest request,
            @Parameter(description = "Who is making the change, recorded in the status audit trail")
            @RequestHeader(value = ACTOR, required = false) String actor) {

        Trip updated = tripService.updateStatus(id, request, actor);
        return ResponseEntity.ok(TripResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a trip",
            description = "Removes the trip and its status history.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Trip deleted"),
            @ApiResponse(responseCode = "404", description = "No trip with that id")
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tripService.delete(id);
    }
}
