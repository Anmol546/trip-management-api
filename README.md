# Trip Management API

A production-oriented backend service for managing trips: create, query, transition and
delete them, with database-enforced lifecycle rules, safe concurrent status updates and
idempotent creates.

Built with **Java 21**, **Spring Boot 3.3**, **Spring Data JPA**, **Flyway** and
**PostgreSQL**. Tests run against in-memory H2 through the same migrations.

---

## Contents

- [Quick start](#quick-start)
- [API](#api)
- [Error format](#error-format)
- [Design decisions](#design-decisions)
- [Concurrency](#concurrency)
- [Duplicate creates and idempotency](#duplicate-creates-and-idempotency)
- [Database schema](#database-schema)
- [Tests](#tests)
- [Project layout](#project-layout)
- [Assumptions](#assumptions)
- [What I would build next](#what-i-would-build-next)

---

## Quick start

### With Docker (PostgreSQL)

```bash
docker compose up -d postgres     # Postgres 16 on localhost:5432
mvn spring-boot:run               # API on localhost:8080
```

Flyway creates the schema and indexes on first start. No manual SQL step.

### Entirely in Docker

```bash
docker build -t trip-management-api .
docker run -p 8080:8080 trip-management-api
```

### Locally without Docker

Either point `DB_URL` at any PostgreSQL instance, or start the app against H2:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

### Swagger

- UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI document: <http://localhost:8080/v3/api-docs>

### Configuration

Everything is overridable by environment variable.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/tripdb` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `trip` / `trip` | Credentials |
| `DB_POOL_SIZE` | `10` | Hikari maximum pool size |
| `PORT` | `8080` | HTTP port |
| `LOG_LEVEL` | `INFO` | Log level for `com.anmol.trip` |

### Build and test

```bash
mvn clean verify
```

---

## API

All endpoints are served under `/api/v1/trips`. They are also mounted under `/trips`,
because that is the path the specification names, so either prefix works.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/trips` | Create a trip |
| `GET` | `/api/v1/trips` | List trips, paginated and filterable |
| `GET` | `/api/v1/trips/{id}` | Fetch one trip |
| `PATCH` | `/api/v1/trips/{id}/status` | Transition status |
| `DELETE` | `/api/v1/trips/{id}` | Delete a trip |
| `GET` | `/api/v1/trips/{id}/history` | Status audit trail (addition) |

### Create a trip

```bash
curl -i -X POST http://localhost:8080/api/v1/trips \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 3f6c1f6e-0f2a-4a3f-9a1b-8d5e2c7b4a10' \
  -d '{"tripNumber":"TRIP-2026-0001","source":"Delhi NCR","destination":"Jaipur"}'
```

```http
HTTP/1.1 201 Created
Location: http://localhost:8080/api/v1/trips/1
```

```json
{
  "id": 1,
  "tripNumber": "TRIP-2026-0001",
  "source": "Delhi NCR",
  "destination": "Jaipur",
  "status": "CREATED",
  "version": 0,
  "createdAt": "2026-10-01T08:30:00Z",
  "updatedAt": "2026-10-01T08:30:00Z"
}
```

`201 Created` on a fresh create. `200 OK` when the `Idempotency-Key` is replayed.
`409 Conflict` if the trip number is taken. `422 Unprocessable Entity` is not possible
here because a new trip is always `CREATED`, which does not need a driver.

### List trips

```bash
curl 'http://localhost:8080/api/v1/trips?status=IN_TRANSIT&page=0&size=20&sort=createdAt,desc'
```

| Parameter | Type | Notes |
|---|---|---|
| `status` | enum | One of the five statuses. Repeatable in the API, single-valued in the UI. |
| `source` | string | Case-insensitive exact match |
| `destination` | string | Case-insensitive exact match |
| `tripNumber` | string | Case-insensitive exact match |
| `createdFrom` | ISO-8601 | Inclusive lower bound on `createdAt` |
| `createdTo` | ISO-8601 | Exclusive upper bound on `createdAt` |
| `page` | int | Zero based. Negative values are clamped to `0`. |
| `size` | int | Default `20`, **capped at 100** |
| `sort` | string | Any property on `Trip`, e.g. `sort=tripNumber,asc` |

Filters combine with `AND`. Default ordering is `createdAt DESC`.

```json
{
  "content": [
    { "id": 1, "tripNumber": "TRIP-2026-0001", "status": "IN_TRANSIT", "...": "..." }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 137,
  "totalPages": 7,
  "first": true,
  "last": false,
  "empty": false
}
```

### Update status

```bash
curl -X PATCH http://localhost:8080/api/v1/trips/1/status \
  -H 'Content-Type: application/json' \
  -H 'X-Actor: ops-user-42' \
  -d '{"status":"ASSIGNED","driverName":"Ravi Kumar"}'
```

Legal moves:

```
CREATED ──▶ ASSIGNED ──▶ IN_TRANSIT ──▶ COMPLETED
   │            │             │
   └────────────┴─────────────┴────────▶ CANCELLED
```

`COMPLETED` and `CANCELLED` are terminal. Backwards moves, skipping ahead and leaving a
terminal status all return `409 INVALID_STATUS_TRANSITION`.

### Delete a trip

```bash
curl -i -X DELETE http://localhost:8080/api/v1/trips/1    # 204 No Content
```

### Status history

```bash
curl http://localhost:8080/api/v1/trips/1/history
```

```json
[
  { "id": 3, "fromStatus": "IN_TRANSIT", "toStatus": "COMPLETED",
    "changedBy": "ops-user-42", "changedAt": "2026-10-01T09:40:00Z" },
  { "id": 2, "fromStatus": "ASSIGNED",   "toStatus": "IN_TRANSIT",
    "changedBy": "ops-user-42", "changedAt": "2026-10-01T09:05:00Z" },
  { "id": 1, "fromStatus": null,         "toStatus": "CREATED",
    "changedBy": "ops-user-42", "changedAt": "2026-10-01T08:30:00Z" }
]
```

Newest first. The oldest entry has a `null` `fromStatus` because it is the implicit move
into `CREATED`.

---

## Error format

Every error is an RFC 9457 `application/problem+json` document. The `code` field is
stable and machine readable, so clients branch on that rather than on prose.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Trip 1 cannot move from IN_TRANSIT to ASSIGNED. Allowed targets from IN_TRANSIT: COMPLETED, CANCELLED",
  "instance": "/api/v1/trips/1/status",
  "code": "INVALID_STATUS_TRANSITION",
  "timestamp": "2026-10-01T09:41:12.184Z",
  "details": {
    "tripId": 1,
    "currentStatus": "IN_TRANSIT",
    "requestedStatus": "ASSIGNED",
    "allowedTransitions": "COMPLETED, CANCELLED"
  }
}
```

Validation failures add a `violations` array and return `400`:

```json
{
  "code": "VALIDATION_ERROR",
  "status": 400,
  "violations": [
    { "field": "tripNumber", "message": "tripNumber must not be blank", "rejectedValue": "  " }
  ]
}
```

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Bean validation failed on the body or a parameter |
| `MALFORMED_REQUEST` | 400 | Body is absent or not valid JSON for the schema |
| `INVALID_PARAMETER` | 400 | A query parameter has the wrong type, or an unknown sort property |
| `TRIP_NOT_FOUND` | 404 | No trip with that id |
| `ENDPOINT_NOT_FOUND` | 404 | Unknown path |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for that path |
| `TRIP_NUMBER_ALREADY_EXISTS` | 409 | Trip number already taken |
| `INVALID_STATUS_TRANSITION` | 409 | The requested move is not legal |
| `IDEMPOTENCY_KEY_REUSED` | 409 | Key replayed with a different body |
| `IDEMPOTENT_REQUEST_IN_PROGRESS` | 409 | The original request for this key is still running |
| `CONCURRENT_UPDATE` | 409 | Lost the update race repeatedly; retry with fresh state |
| `DATA_CONFLICT` | 409 | A database constraint fired that no layer translated |
| `DRIVER_REQUIRED` | 422 | Target status needs a driver and the trip has none |
| `INTERNAL_ERROR` | 500 | Unexpected failure; details are logged, never returned |

---

## Design decisions

### Lifecycle rules live in the enum, not in the controller

`TripStatus` declares its own allowed targets. The controller does not know what a valid
transition is, the service does not hard-code a table, and the database enforces the
driver rule independently. Adding a rule means editing one map.

### Status changes are a conditional UPDATE, not an entity save

`TripRepository.updateStatus` issues a single `UPDATE ... WHERE id = ? AND status = ?
AND version = ?`. This is the single most important decision in the service and the rest
of this section explains it. The alternative, `SELECT` then `save` with `@Version`, only
detects the conflict after the fact; the conditional statement prevents it.

### A PATCH that re-sends the current state is a no-op, not an error

If the trip is already in the requested status and the driver is unchanged, the trip is
returned unchanged with `200 OK`. This is what makes `PATCH` safe to retry: a client that
retries after a timeout, or that lost a race to an identical request, does not have to
interpret a `409` as a failure.

### Trip numbers are stored upper-cased

The unique index on `trips.trip_number` is case sensitive, which is the default on
PostgreSQL. Canonicalising on write is what makes it behave case-insensitively, without
needing a functional index. `trip-1`, ` TRIP-1 ` and `TRIP-1` are the same trip.

### Timestamps are `TIMESTAMP` holding UTC

`Instant` is persisted as `TIMESTAMP` (no time zone) with the JDBC session pinned to UTC
via `hibernate.jdbc.time_zone`. This keeps one migration valid on both PostgreSQL and the
H2 test database; storing UTC instants in a zoned column would need two dialects.

### No Lombok

Records for DTOs, explicit accessors for entities. Verbose, but nothing is hidden behind
an annotation processor and the entity mappings read top to bottom.

### Filtering through `Specification`

Adding a filter is one predicate in `TripSpecifications` plus one query parameter, rather
than a growing pile of repository methods.

### Driver assignment is a first-class concern

A driver is required before a trip can be `ASSIGNED`, `IN_TRANSIT` or `COMPLETED`. This is
enforced in three places on purpose: the service returns `422`, the database has a `CHECK`
constraint, and re-assigning a driver without changing status is allowed because
operations really do dispatch to a different vehicle mid-trip.

---

## Concurrency

The requirement is that two simultaneous status updates leave a consistent final state.
The mechanism is a compare-and-set:

```sql
UPDATE trips
   SET status = :to, driver_name = :driver, version = version + 1, updated_at = :now
 WHERE id = :id AND status = :from AND version = :version
```

Both the status and the version the caller observed have to still be true. Therefore:

- Exactly one of N concurrent writers can apply a given transition. The others get
  `rowsUpdated == 0` and learn they lost.
- No row lock is held for longer than that one statement, so a burst of updates on the
  same trip serialises cheaply rather than blocking a transaction for its whole duration.
- A writer can never overwrite a state it did not observe. Reading `CREATED` and then
  writing over a trip that has since reached `COMPLETED` is not expressible.

Losers do not fail. `TripService.updateStatus` re-reads and retries up to three times,
re-validating the transition against the *new* state. So two concurrent `ASSIGNED` calls
both end in `200 OK` with the trip assigned, and exactly one audit row is written. If the
new state makes the transition illegal, the retry turns into a `409`, which is the correct
answer. After three losses the caller gets `409 CONCURRENT_UPDATE` and is told to retry,
rather than being handed a status nobody committed.

The optimistic `@Version` column is retained as well, so any path that does mutate the
managed entity is still protected.

The tests in `TripConcurrencyIntegrationTest` release eight real HTTP requests from one
latch and assert the invariants: one recorded move per transition, a trip that stays
terminal once terminal, no `5xx` under contention, and exactly one row created from eight
identical `POST`s.

---

## Duplicate creates and idempotency

There are two layers, because they solve different problems.

**1. The unique index.** `trips.trip_number` is unique, so a duplicate create is
impossible at the storage level no matter how many requests arrive. The loser gets a clean
`409`, not a second row. This alone satisfies the requirement.

**2. `Idempotency-Key`.** A unique index can only tell the client that its create failed.
The key turns a retry into a success. `IdempotencyService` runs claim → create → complete
(or release), each step in its own transaction:

| Step | What it does |
|---|---|
| `claim` | Inserts a row keyed by the client key. The unique index decides exactly one winner; the losers read the winner's row. |
| create | Runs in the caller's transaction. |
| `complete` | Stores the new trip id on the claim, publishing the result. |
| `release` | Deletes the claim if anything failed, so the client can retry the same key. |

Each claim also stores a SHA-256 fingerprint of the normalised request body. Replaying a
key with a *different* body returns `409 IDEMPOTENCY_KEY_REUSED` instead of silently
handing back the first trip, which would hide a client bug.

Losers wait up to two seconds for the original to publish its trip, then return it with
`200 OK` and an `Idempotency-Replayed: true` header. If the original is still running they
get `409 IDEMPOTENT_REQUEST_IN_PROGRESS` and should retry — the safe outcome, since
creating a second trip is the thing we are preventing.

Claims older than ten minutes with no attached trip are assumed to belong to a crashed
request and are reclaimed, so the table self-heals. A scheduled purge of completed claims
is listed as follow-up work below.

---

## Database schema

Flyway owns the schema; Hibernate is set to `validate`, so a drift between the mapping and
the migration fails the build/startup instead of failing at runtime.

The migration is written in the common subset of PostgreSQL and H2 so one migration history
serves production and tests.

### `trips`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT` | Identity, primary key |
| `trip_number` | `VARCHAR(50)` | **Unique**, stored upper-cased |
| `source` | `VARCHAR(120)` | Not blank |
| `destination` | `VARCHAR(120)` | Not blank |
| `driver_name` | `VARCHAR(120)` | Nullable; required by a `CHECK` once the trip is operating |
| `status` | `VARCHAR(20)` | `CHECK` against the five statuses |
| `version` | `BIGINT` | Optimistic lock counter, bumped by the conditional update |
| `created_at` / `updated_at` | `TIMESTAMP` | UTC |

Constraints:

```sql
uk_trips_trip_number   UNIQUE (trip_number)
ck_trips_status        status IN ('CREATED','ASSIGNED','IN_TRANSIT','COMPLETED','CANCELLED')
ck_trips_driver_required
                       status NOT IN ('ASSIGNED','IN_TRANSIT','COMPLETED')
                       OR driver_name IS NOT NULL
ck_trips_*_not_blank   TRIM(...) <> ''
```

Indexes, each justified by a query the service actually issues:

| Index | Serves |
|---|---|
| `idx_trips_status_created_at (status, created_at DESC)` | The default listing filtered by status |
| `idx_trips_created_at (created_at DESC)` | The default listing with no status filter |
| `idx_trips_source_destination (source, destination)` | Filtering by route |
| `uk_trips_trip_number` | Uniqueness, and lookups by business key |

### `trip_status_history`

Append-only audit trail, written in the same transaction as the transition it records.
`trip_id` has `ON DELETE CASCADE`, so deleting a trip cannot leave orphaned history.
Indexed on `(trip_id, changed_at DESC)` for the per-trip read.

### `idempotency_keys`

`(idempotency_key)` unique — the mechanism that collapses concurrent duplicates —
plus `request_fingerprint`, the resolved `trip_id`, and `created_at` for reclamation.

---

## Tests

61 tests, all passing, on H2 with the real Flyway migrations.

```bash
mvn test
```

- `TripStatusTest` — the lifecycle rules in isolation: allowed edges, terminal statuses,
  no backwards moves, no skips.
- `TripApiIntegrationTest` — the full HTTP surface through MockMvc: validation, uniqueness,
  pagination and its cap, filtering, sorting, every transition and rejection path, the
  audit trail, deletion, and the exact error body shape.
- `TripConcurrencyIntegrationTest` — eight simultaneous requests released from a single
  latch, covering identical transitions, competing targets, a race to a terminal status,
  illegal transitions under load, and duplicate creates with and without an idempotency
  key.

---

## Project layout

```
src/main/java/com/anmol/trip/
  TripManagementApplication.java
  config/        Clock bean, OpenAPI metadata
  domain/        Trip, TripStatus (the rules), TripStatusHistory, IdempotencyKey
  exception/     ApiException and one type per failure, each carrying an HTTP status + code
  repository/    TripRepository (incl. the conditional update), specs, history, idempotency
  service/       TripService, IdempotencyService, request normalisation, page guard rails
  web/           TripController, GlobalExceptionHandler, request/response DTOs
src/main/resources/
  application.yml
  db/migration/V1__create_trip_schema.sql
src/test/java/com/anmol/trip/
  domain/        Lifecycle unit tests
  web/           API and concurrency integration tests
```

Layering is strict: controllers do not touch repositories, services do not know about
HTTP status codes beyond their exceptions, and repositories contain no business rules.

---

## Assumptions

The specification left these open. I picked the behaviour below and made it visible in
the API rather than guessing silently.

1. **A trip starts in `CREATED`** and cannot be created directly in another status.
2. **`CANCELLED` is reachable from `CREATED`, `ASSIGNED` and `IN_TRANSIT`.** The spec named
   it without saying from where. `CANCELLED → CANCELLED` is the only terminal move allowed,
   and it is a no-op rather than an error.
3. **`driverName` is optional at creation** and required before a trip can be assigned.
4. **Trip numbers are compared case-insensitively.**
5. **`source` and `destination` are free text.** No geospatial or address validation,
   because the spec gave no reference data to validate against.
6. **`source` and `destination` may be equal.** Rejecting it would be an unrequested rule.
7. **Deletion is allowed in any status**, including `IN_TRANSIT`. This is the one place I
   would change the behaviour given more time — see below.
8. **Deletion is hard.** The audit trail cascades with it. A system with a retention
   requirement would soft-delete instead.
9. **`X-Actor` is optional.** When present it is recorded in the audit trail; there is no
   authentication in this service.
10. **All timestamps are UTC.**

---

## What I would build next

Deliberately left out, in the order I would do them.

1. **Refuse to delete a trip that is `IN_TRANSIT`.** Right now `DELETE` is unconditional.
   The check is three lines in `TripService.delete`, but it changes existing behaviour, so
   it belongs in a decision rather than an assumption.
2. **Scheduled cleanup of resolved idempotency claims.** They are reclaimed on access after
   ten minutes, but nothing removes the ones that completed successfully. A nightly
   `DELETE FROM idempotency_keys WHERE created_at < ?` keeps the table small.
3. **AuthN/AuthZ.** `X-Actor` is trusted on faith. Operator-only routes such as `DELETE`
   need a real identity, and the audit trail needs an immutable actor.
4. **Optimistic request rejection (`If-Match`).** `version` is already on the response.
   Echoing it back in `If-Match` would let a client detect a stale read instead of
   relying on the server-side retry.
5. **Rate limiting on the listing endpoint** and a hard cap on `page` depth.
6. **Cursor pagination.** `page`/`size` degrades on deep offsets; a keyset cursor over
   `(created_at, id)` would be stable under concurrent inserts.
7. **Real source/destination entities.** Free-text city names cannot be aggregated on, and
   they admit typos.
8. **An outbox and a trip-event stream.** Dispatch and billing systems need to react to
   status changes; polling the history table does not scale.
9. **Testcontainers for PostgreSQL.** H2 is not PostgreSQL. The migrations are written in
   the common subset specifically so this swap is cheap, and CI should do it.
10. **OpenTelemetry tracing** around the transition retries, which is where latency hides.
