# LedgerFlow API — Implementation Roadmap

This backlog turns the approved design in [LEDGERFLOW_API_DESIGN.md](LEDGERFLOW_API_DESIGN.md) into small, ordered user stories. Implement stories in sequence. Each story should leave the project buildable and should be committed separately if version control is used.

This document intentionally describes work and acceptance criteria rather than providing application code. The API remains a local, in-memory learning application: do not add databases, Docker Compose, persistence, authentication, messaging, or unrelated framework layers.

## How to use this roadmap

- Complete the stories in order; later stories rely on contracts introduced earlier.
- Read the linked design document before beginning a story. It remains the source of truth for endpoint contracts, errors, locking, and domain behavior.
- Keep implementations small: controllers handle HTTP, services apply rules, repositories own maps, and DTOs define JSON.
- Write only the tests named in each story. Prefer direct service tests; use MVC tests when behavior belongs to HTTP binding or response formatting.
- Run the relevant tests after each story. At story boundaries, run the whole test suite.
- Do manual/JMeter validation after the functional sequence is available. Do not try to automate every JMeter scenario in JUnit.

## Definition of done

A story is complete when all its acceptance criteria are met, its stated tests pass, no out-of-scope dependency has been added, and the application still builds with Java 25. Keep DTO/domain/package names aligned with the design document.

Use UTC timestamps, BigDecimal for all money, and immutable snapshots across repository/service boundaries. Never use float or double for money.

## Story map

| Order | Story | Outcome |
| --- | --- | --- |
| 1 | Bootstrap the application | A minimal Boot application builds and starts |
| 2 | Provide health and base configuration | Health is exposed and UTC is available |
| 3 | Provide operational logging and request correlation | Safe, structured request logs identify each call |
| 4 | Establish domain and shared in-memory state | Seed accounts and locking foundation exist |
| 5 | Read accounts | Clients can list and inspect accounts/balances |
| 6 | Standardize API errors and input behavior | Invalid requests return predictable JSON |
| 7 | Create atomic transfers | Funds move safely with complete transaction records |
| 8 | Make transfers idempotent | Retries do not duplicate movement |
| 9 | Expose transfer creation over HTTP | Clients can create and correlate transfers |
| 10 | Read transactions and history | Clients can retrieve stable transaction snapshots |
| 11 | Reverse completed transfers | Clients can safely compensate one transfer |
| 12 | Reset the simulation | Test suites can restore the initial state |
| 13 | Protect concurrency invariants | Concurrent requests preserve correctness |
| 14 | Containerize the application | The API runs in one Docker container |
| 15 | Run the JMeter tutorial acceptance pass | The tutorial scenarios are proven manually |

## Story 1 — Bootstrap the application

**User story:** As a developer, I want a minimal Java 25 Spring Boot application so that I can run and test the API locally without external infrastructure.

### Implementation tasks

1. Convert the existing Maven project to use a pinned stable Spring Boot 4.1.x parent or dependency-management arrangement.
2. Keep the project coordinates unless there is a deliberate naming decision. Set Java compilation to release 25 rather than separate source/target settings where practical.
3. Add only these initial dependencies:
   - Spring Boot MVC starter (`spring-boot-starter-webmvc` for Boot 4).
   - Bean Validation starter.
   - Spring Boot Actuator.
   - Boot-managed test starter in test scope.
4. Configure the Spring Boot Maven plugin so packaging produces an executable JAR.
5. Create one application entry-point class in base package `org.vass.ledgerflow` so component scanning reaches all planned packages.
6. Set the application port to 8080 and the application name to `ledgerflow-api`.
7. Add a Maven Wrapper if it is practical in the development environment, pinning its Maven version.

### Acceptance criteria

- `mvn clean verify` succeeds with Java 25.
- `mvn spring-boot:run` starts an embedded server on port 8080.
- A packaged JAR starts through `java -jar`.
- The project contains no JPA, Spring Data, database driver, H2, cache-server, broker, or container-orchestration dependency.
- The current source tree contains no application feature code beyond the entry point/configuration required to start.

### Minimum automated verification

- One context-load test is sufficient at this stage. It proves the Boot configuration can create an application context.
- Do not test framework behavior that Spring Boot already owns.

### Manual check

- Start the application and confirm the process listens on `http://localhost:8080`.

## Story 2 — Provide health and base configuration

**User story:** As a tutorial user, I want a standard health endpoint so that I can confirm the application is running before executing requests or a JMeter test plan.

### Implementation tasks

1. Configure Actuator to expose only health over HTTP. Do not expose environment, mappings, loggers, heap dumps, or additional operational endpoints.
2. Confirm that the health endpoint remains at `GET /actuator/health`.
3. Provide a singleton UTC `Clock` through configuration. Services must receive it through constructor injection rather than calling `Instant.now()` directly.
4. Set application timestamp serialization/configuration to produce ISO-8601 UTC instants. Verify the chosen Boot 4/Jackson 3 configuration rather than relying on deprecated Jackson 2 examples.
5. Decide whether actuator health detail is hidden. Use the default simple local response unless a specific tutorial requirement needs more detail.

### Acceptance criteria

- `GET /actuator/health` returns 200 and `{"status":"UP"}` while the application is healthy.
- Business endpoints remain under `/api/v1`; no custom health controller is introduced.
- Application services can accept the configured clock through constructor injection.

### Minimum automated verification

- One MVC test for `GET /actuator/health` asserting 200 and status `UP`.
- One narrow configuration test only if needed to prove the injected clock uses UTC.

### Manual check

- Call health before and after a local application restart. It should not depend on an external service.

## Story 3 — Provide operational logging and request correlation

**User story:** As a developer and tutorial user, I want safe, structured, correlated application logs so that I can diagnose requests without exposing financial or client data.

### Implementation tasks

1. Use the SLF4J API with Spring Boot's default Logback implementation already supplied by the existing starters. Do not add Log4j, Log4j2, another logging implementation, or a custom `logback-spring.xml` file.
2. Emit logs only to standard output. Do not create local log files; local terminals and container runtimes collect stdout.
3. Configure structured Logstash JSON console logging with Boot's built-in support. Set root and `org.vass.ledgerflow` loggers to INFO, and `org.springframework.web` to WARN.
4. Add a request-correlation filter that accepts an `X-Request-Id` value of 1–64 ASCII letters, digits, `.`, `_`, or `-`, or generates a UUID when it is absent or invalid. Return the resolved ID in the `X-Request-Id` response header.
5. Put the resolved request ID in MDC for the lifetime of the request and always remove it in a `finally` block. This must work correctly with servlet-thread reuse.
6. After the filter chain completes, emit exactly one INFO completion event for each `/api/v1` request. Include only request ID, HTTP method, matched route template without identifiers or query parameters, response status, and elapsed duration in milliseconds. Do not emit this event for `/actuator/health` at INFO level.
7. Never log request or response bodies, `Idempotency-Key` values, credentials, cookies, holder names, account identifiers, balances, transfer amounts, or descriptions. Do not add log collection, tracing, or an external observability service.

### Acceptance criteria

- Console output is one valid Logstash JSON object per log event and includes the MDC request ID when it exists.
- Every HTTP response, including health, contains a nonblank `X-Request-Id` header.
- A valid incoming request ID is returned unchanged; a missing or invalid value is replaced with a server-generated UUID.
- A business API request produces one completion event with only the approved operational fields.
- Health probes do not produce an INFO completion event.
- No configured logger writes to a local file, and no sensitive request/business data appears in log messages or MDC.

### Minimum automated verification

- Extend the health MVC test to assert 200, `UP`, and a nonblank `X-Request-Id` response header.
- Send a valid `X-Request-Id` to health and assert that the response preserves it.
- Unit-test the correlation filter's invalid/missing-header behavior and verify MDC is cleared after the filter chain returns. Use a mock filter chain; do not parse captured console logs.

### Manual check

- Call health and inspect console output. Confirm structured startup logs, the response correlation header, and no INFO request-completion event for health.

## Story 4 — Establish domain and shared in-memory state

**User story:** As the API, I want exact seeded account state and one coordinated in-memory state boundary so that all later operations start from known data and can be made safe under concurrency.

### Implementation tasks

1. Create the planned package structure: `controller`, `service`, `repository`, `domain`, `dto`, `exception`, and `config`.
2. Define immutable domain representations for:
   - `Account`: account ID, holder name, BigDecimal balance, and USD currency.
   - `Transaction`: fields specified in the design, including type/status/link fields.
   - Transaction type and status enums.
   - A canonical transfer request value and idempotency entry type. They may remain unused until story 8, but defining them now prevents a later ad hoc model.
3. Create a seed-data factory containing exactly:
   - `ACC-001`, Alice Johnson, 10000.00 USD.
   - `ACC-002`, Brian Miller, 5000.00 USD.
   - `ACC-003`, Acme Services LLC, 100000.00 USD.
4. Build concrete in-memory repositories with private maps for accounts, transactions, and idempotency entries. Use `ConcurrentHashMap` if desired, but do not treat it as the transaction mechanism.
5. Create a single, fair `ReentrantReadWriteLock` coordinator shared by every repository/service using the state.
6. Ensure the coordinator object stays stable for the application lifetime; reset must later clear/replace map values without replacing the coordinator.
7. Seed account state before the application accepts requests. Repositories must never expose live mutable map values.
8. Use exact decimal construction and normalize seeded balances to two decimal places.

### Acceptance criteria

- The startup state contains exactly three accounts and no transactions or idempotency entries.
- Account IDs and holder names match the design exactly.
- The seed balance total is exactly 115000.00 USD using BigDecimal comparison appropriate to the chosen scale policy.
- Domain values returned from repositories cannot be used to mutate repository state outside the coordinator.
- All future state access has a visible path to the same lock coordinator.

### Minimum automated verification

- One repository/seed test asserting the three full account values, empty transaction state, and 115000.00 total.
- One small test proving a retrieved account snapshot cannot alter stored state, if the chosen domain representation makes this non-obvious.

### Manual check

- Restart the application and confirm the same three seed accounts are recreated once account endpoints exist.

## Story 5 — Read accounts

**User story:** As a tutorial user, I want to list accounts and retrieve a single account or its balance so that I have stable starting data for functional and load-test requests.

### Implementation tasks

1. Define response DTOs for account detail and balance. Do not serialize domain objects directly.
2. Implement an account query service that acquires the shared read lock, copies immutable state into DTO/domain snapshots, then releases the lock before response serialization.
3. Implement these endpoints:
   - `GET /api/v1/accounts`
   - `GET /api/v1/accounts/{accountId}`
   - `GET /api/v1/accounts/{accountId}/balance`
4. Sort the list by `accountId` ascending.
5. Return account ID, holder name, balance, and currency for account details; return account ID, balance, and currency for balances.
6. Add `Cache-Control: no-store` to business API responses.
7. Establish a focused account-not-found exception that story 6 will map to a standard 404 body. Until story 6, complete the exception mapping required for these endpoints rather than relying on a default error page.

### Acceptance criteria

- Account list returns 200 and the three seed accounts in ascending ID order.
- Account detail and balance return 200 for each seed account.
- Balance values are JSON numbers and originate from BigDecimal.
- Querying an unknown, syntactically valid ID returns 404.
- Account reads do not change state.
- A list snapshot cannot show a partial transfer once write operations are added.

### Minimum automated verification

- MVC test: list returns 200, length three, ascending IDs, and expected balances.
- MVC test: account detail and balance for `ACC-001` return expected fields and values.
- MVC test: unknown account returns 404 in the project error format.
- Direct service test: query calls do not alter any account balance.

### Manual check

- Use a REST client to compare `ACC-001` detail balance and balance-endpoint balance.

## Story 6 — Standardize API errors and input behavior

**User story:** As a tutorial user, I want every predictable API failure to use one JSON error format so that I can create reliable JMeter assertions.

### Implementation tasks

1. Define an error response DTO containing `timestamp`, `status`, `error`, `code`, `message`, `path`, and optional validation `details`.
2. Add a central exception handler for `/api/v1` controller/service failures. Do not wrap Actuator errors in this contract.
3. Define focused application exceptions or an equivalent typed error model for at least account not found, transaction not found, conflicts, and business-rule failures.
4. Translate JSON parse failures, unreadable request bodies, validation errors, invalid path/query values, missing headers, unsupported media type, unacceptable response type, unknown API routes, and unsupported methods to the documented statuses/codes.
5. Configure JSON input strictly for business DTOs:
   - reject unknown fields;
   - reject duplicate object keys;
   - reject inappropriate type coercion, including numeric strings for amount;
   - require a JSON object for transfer creation.
6. Establish transfer request DTO validation for source account ID, destination account ID, amount presence/type/size/precision, and optional description. The transfer service will enforce state-dependent rules later.
7. Decide and consistently implement account-ID format validation: nonblank, at most 64 characters, no whitespace, case-sensitive.
8. Ensure error timestamp comes from the injected UTC clock where the handler can access it.
9. Avoid stack traces, exception class names, and internal implementation details in response bodies. Expected 4xx failures must not produce error-level stack traces. Log an unexpected 5xx failure once at ERROR with the request ID, method, sanitized route template, status, and exception; do not log the request body or business data.

### Acceptance criteria

- Handled `/api/v1` errors contain all required fields with a UTC ISO timestamp and a path without query parameters.
- Validation details are present only when field-level validation failed.
- Malformed JSON returns 400 `MALFORMED_JSON`.
- Unknown JSON fields and invalid formats return 400 `VALIDATION_ERROR`.
- An unknown account returns 404 `ACCOUNT_NOT_FOUND`.
- An unexpected exception produces 500 `INTERNAL_ERROR` without a stack trace in the body.
- Expected 4xx responses do not emit an exception stack trace; an unexpected 5xx response has one sanitized ERROR event associated with its request ID.
- Actuator health continues to use its own response format.

### Minimum automated verification

- MVC test: malformed JSON returns the 400 body shape and `MALFORMED_JSON`.
- MVC test: unknown transfer-request field returns 400 `VALIDATION_ERROR` with a field detail.
- MVC test: unknown account returns 404 with `ACCOUNT_NOT_FOUND` and expected path.
- MVC test: missing business route returns 404 `RESOURCE_NOT_FOUND`.
- One handler-level test for 500 body redaction if this cannot be covered cleanly through MVC.

### Manual check

- Submit malformed JSON, an unknown property, and a bad account ID. Compare status, `code`, and `path` to the design document.

## Story 7 — Create atomic transfers

**User story:** As a tutorial user, I want to transfer funds between two valid accounts so that I can observe correct balances and a retrievable transaction record.

### Implementation tasks

1. Finalize the `Transaction` model and transaction repository operations needed to store/retrieve immutable records by generated UUID.
2. Implement a transfer application service. It owns the complete movement; neither controller nor repository may perform one side of the balance update.
3. Acquire the shared write lock before reading mutable account state. Never attempt a read-to-write lock upgrade.
4. Inside the lock, validate new transfer business rules in this order:
   1. amount is strictly greater than zero;
   2. source account exists;
   3. destination account exists;
   4. accounts differ;
   5. source account has sufficient funds.
5. Normalize a valid amount to exact scale two without rounding. Accept trailing-zero representations such as 150.750 only if they are exactly representable as cents; reject amounts with meaningful digits beyond cents.
6. Generate a UUID transaction ID in the application and capture a UTC creation instant from the injected clock.
7. Prepare replacement account snapshots, a completed transfer transaction, and a response snapshot before publishing state.
8. Publish source debit, destination credit, and transaction record while holding the write lock. Release in a `finally` path.
9. Map business rules to the documented 422/404 codes. An insufficient-funds failure must not add a transaction or alter either balance.
10. Do not add idempotency behavior yet; story 8 adds it under the same write lock.

### Acceptance criteria

- A successful transfer debits the source and credits the destination by exactly the same BigDecimal amount.
- Success creates one `TRANSFER` transaction with type `TRANSFER`, status `COMPLETED`, USD, generated ID, UTC timestamp, and the requested description.
- The source never becomes negative.
- Total account funds remain 115000.00 after any successful or rejected transfer.
- Insufficient funds returns 422 `INSUFFICIENT_FUNDS` and changes neither account nor transaction history.
- A transfer to the same account returns 422 `SAME_ACCOUNT_TRANSFER` with no state change.
- Zero/negative amounts return 422 `INVALID_AMOUNT` with no state change.
- Missing source/destination accounts return 404 and leave state unchanged.

### Minimum automated verification

- Direct service test: successful 150.75 transfer changes `ACC-001` to 9849.25 and `ACC-002` to 5150.75; transaction is complete; total is conserved.
- Direct service test: insufficient funds leaves both account values and transaction count unchanged.
- Parameterized direct service test: zero and negative amounts fail without state changes.
- Direct service test: same-account transfer fails without a transaction.
- Direct service test: missing source and missing destination each produce the correct not-found failure.

### Manual check

- Execute one transfer using a temporary client request and verify both balances. Restart afterward if reset has not yet been implemented.

## Story 8 — Make transfers idempotent

**User story:** As a client that may retry timed-out requests, I want a transfer key to safely identify one successful operation so that retrying does not move money twice.

### Implementation tasks

1. Require exactly one `Idempotency-Key` header for transfer creation. Validate it before service invocation: 1–128 characters from letters, digits, `.`, `_`, `:`, and `-`; header values are case-sensitive.
2. Extend the transfer service to accept the validated key.
3. Build a canonical request value from source account ID, destination account ID, normalized amount, and normalized description. Treat missing description and null as equivalent; preserve all other description content exactly.
4. Under the existing write lock, check the idempotency map before new-operation business validation:
   - matching canonical request: return the saved original success-response snapshot;
   - different canonical request: return 409 `IDEMPOTENCY_KEY_CONFLICT`;
   - absent key: continue as a new operation.
5. For a new successful transfer, publish the idempotency entry together with both account updates and the transaction record, all while holding the same write lock.
6. Store the immutable success response as it was at creation: type `TRANSFER`, status `COMPLETED`, and no reversal links.
7. Do not store keys for parsing, header-validation, validation, account-not-found, same-account, or insufficient-funds failures.
8. Ensure no `computeIfAbsent`, separate key lock, or lock hierarchy is added. The state coordinator is the single boundary.
9. Do not log an idempotency key, canonical request, replay payload, or conflict payload. The request-completion event from story 3 is sufficient for operational visibility.

### Acceptance criteria

- The first valid request with a fresh key creates one transfer.
- A repeated key with the same canonical payload returns the original transaction/response snapshot and causes no second debit, credit, transaction, or idempotency entry.
- A repeated key with a different amount, account, or non-equivalent description returns 409 `IDEMPOTENCY_KEY_CONFLICT` and changes no state.
- Reordered JSON properties and 150.75 versus 150.750 are considered the same payload.
- A failed attempt does not consume a key; the same key can later complete if the request becomes valid/possible.
- Missing, blank, repeated, or invalid headers return 400 `INVALID_IDEMPOTENCY_KEY`.
- Idempotency keys do not appear in application logs, including conflict and replay paths.

### Minimum automated verification

- Direct service test: same key/payload called twice produces one transaction and one net movement, returning the same ID both times.
- Direct service test: same key with a changed amount produces a conflict and leaves balances/count unchanged after the first success.
- Direct service test: equivalent trailing-zero amount and reordered input representation replay the original success.
- Direct service test: insufficient-funds attempt does not reserve the key; after a suitable state change, the same key can succeed.
- MVC test: missing and invalid idempotency headers return 400 `INVALID_IDEMPOTENCY_KEY`.

### Manual check

- Send the same complete POST request repeatedly with one key. Expect one 201 and subsequent 200 responses with the same transaction ID.

## Story 9 — Expose transfer creation over HTTP

**User story:** As a tutorial user, I want a complete transfer-creation HTTP contract so that I can send requests, extract a transaction ID, and distinguish first submission from replay.

### Implementation tasks

1. Implement `POST /api/v1/transfers` with `application/json` request handling and JSON response production.
2. Bind the request DTO, validate it, read the required idempotency header, and delegate only to the transfer service.
3. Define a transaction response DTO with every documented field:
   - transactionId, type, status, sourceAccountId, destinationAccountId, amount, currency, description, createdAt, idempotencyKey, originalTransactionId, reversalTransactionId, reversedAt.
4. Return 201 for a newly committed transfer and 200 for a matching replay.
5. For both 201 and 200, return the Location of `/api/v1/transfers/{transactionId}` and `Cache-Control: no-store`.
6. Ensure the body for a replay is the saved creation snapshot, not an accidental lookup of mutable current transaction state.
7. Support 400 for wrong content type, unreadable JSON, invalid body/header, and `Accept` values that exclude JSON; retain defined 404/409/422 mappings.

### Acceptance criteria

- A valid create request returns 201, a complete documented response object, and a Location header pointing to its transaction ID.
- A same-key replay returns 200, same Location, same body snapshot, and no state change.
- Currency is server-controlled USD; a client-supplied `currency` field is rejected as unknown.
- JSON numbers serialize money without scientific notation for normal accepted amounts.
- The controller does not know repository internals or acquire state locks.

### Minimum automated verification

- MVC test: valid create returns 201, Location, all required fields, and expected amount/status.
- MVC test: replay returns 200 with same transaction ID and Location.
- MVC test: unsupported media type returns 415 `UNSUPPORTED_MEDIA_TYPE`.
- MVC test: client `Accept` excluding JSON returns 406 `NOT_ACCEPTABLE`, if explicitly configured.

### Manual check

- Create a transfer, use a JSON extractor in a small JMeter test or another client to capture `transactionId`, and inspect the response headers.

## Story 10 — Read transactions and history

**User story:** As a tutorial user, I want to retrieve a transaction and browse transfer history so that I can correlate requests and assert the final effects of a test run.

### Implementation tasks

1. Implement `GET /api/v1/transfers/{transactionId}` with UUID path validation.
2. Return the current transaction snapshot by ID. Later, an original transfer may have status `REVERSED`; do not return the saved idempotency creation response here.
3. Implement `GET /api/v1/transfers` with optional `accountId`, `offset`, and `limit` query parameters.
4. Validate `offset` as integer ≥ 0 and `limit` as integer 1–200, defaulting to 0 and 50.
5. If an account filter is present, validate its format and check that the account exists; return 404 when it does not.
6. Take one transaction/account snapshot under the shared read lock, release the lock, then filter, sort, and paginate the immutable snapshot.
7. Include records where the filter account is either source or destination.
8. Sort history by `createdAt` ascending and `transactionId` ascending for timestamp ties.
9. Return an envelope with `items`, `offset`, `limit`, and `total`. Total is the number of matching records before pagination.
10. Return both original transfers and later reversal records after story 11; no status filter is required.

### Acceptance criteria

- A valid transaction ID returns 200 with the complete current transaction representation.
- A malformed UUID returns 400 `VALIDATION_ERROR`; a well-formed unknown UUID returns 404 `TRANSACTION_NOT_FOUND`.
- An empty history returns the documented empty envelope with total 0.
- History respects account filtering, deterministic sort, offsets, limits, and total count.
- Offset beyond the final record returns 200 with empty `items` and unchanged total.
- Unknown query parameters return 400.
- Reads neither modify state nor serialize live map objects.

### Minimum automated verification

- MVC test: retrieve a known transaction returns 200 and all expected fields.
- MVC test: malformed and unknown transaction IDs return 400 and 404 respectively.
- Direct service or MVC test: history filters to one account, returns expected total, and uses ascending deterministic order.
- MVC test: pagination defaults, an empty page beyond range, invalid offset, and limit above 200.
- MVC test: missing filter account returns 404 `ACCOUNT_NOT_FOUND`.

### Manual check

- Create two transfers through different account pairs, then inspect full and account-filtered history.

## Story 11 — Reverse completed transfers

**User story:** As a tutorial user, I want to reverse a completed original transfer once so that I can demonstrate compensating financial operations without deleting history.

### Implementation tasks

1. Implement `POST /api/v1/transfers/{transactionId}/reversal` with a UUID path and zero request-body bytes required.
2. Reject a nonempty body with 400 `VALIDATION_ERROR`; no idempotency key is required or used for reversal.
3. Implement reversal in the transfer service under the shared write lock.
4. Locate the target transaction and validate in this order:
   1. transaction exists;
   2. it is an original `TRANSFER` record;
   3. it is still `COMPLETED`, not already reversed;
   4. the original destination account can now cover the original amount.
5. Treat reversal as a new compensating movement: debit original destination and credit original source by the original amount.
6. Create a new UUID `REVERSAL` transaction with `COMPLETED` status, source/destination reversed, description `Reversal of {originalTransactionId}`, and `originalTransactionId` set.
7. Replace the original transaction snapshot with one having status `REVERSED`, `reversalTransactionId`, and `reversedAt` set. Preserve original ID, request details, and original creation time.
8. Publish both account values, the changed original, and the new reversal record atomically before releasing the lock.
9. Return 200 with the new reversal response and Location pointing to it.
10. Do not modify the original idempotency entry. A later replay of its create request must return the saved original completed response snapshot.

### Acceptance criteria

- Reversing a completed transfer creates exactly one separate reversal record and restores that transfer's net balance effect.
- The original becomes `REVERSED` and links to the reversal; the reversal links to the original.
- A repeated reversal returns 409 `TRANSFER_ALREADY_REVERSED` and adds no record/movement.
- Reversing a reversal returns 422 `TRANSACTION_NOT_REVERSIBLE`.
- Reversing an unknown ID returns 404; malformed IDs return 400.
- If original destination funds are insufficient, return 422 `INSUFFICIENT_REVERSAL_FUNDS`, preserve original `COMPLETED`, and change no balance/history.
- No account may become negative; total funds remain conserved.
- A create-transfer replay after reversal returns its original completed creation snapshot; direct GET returns the original's current reversed state.

### Minimum automated verification

- Direct service test: create then reverse 150.75; balances return to seed values, history gains a second record, and links/statuses are correct.
- Direct service test: reverse twice; first succeeds, second conflicts, transaction count remains two.
- Direct service test: reversal of a reversal fails with no state change.
- Direct service test: drain the original destination after the original transfer, then verify reversal returns insufficient reversal funds with no state change.
- Direct service test: original create replay after reversal returns saved completed snapshot while transaction lookup reports reversed.
- MVC test: successful reversal returns 200, Location, and reversal type.

### Manual check

- Create a transfer, reverse it, retrieve both IDs, inspect history, and verify balances. Then repeat the reversal request to observe 409.

## Story 12 — Reset the simulation

**User story:** As a test-suite operator, I want one administrative reset endpoint so that each functional or load test can begin from the same state.

### Implementation tasks

1. Implement `POST /api/v1/admin/reset` with a zero-byte body requirement.
2. Reuse the exact seed-data factory from story 4. Do not duplicate literal account values in the reset service.
3. Acquire the shared write lock, restore the three account values, clear transactions, and clear idempotency entries as one logical operation.
4. Keep the shared lock instance in place. Do not construct a new coordinator or let requests retain a reference to a discarded one.
5. Return 204 with no body and no Location header only after reset has completed.
6. Reject a nonempty request body with 400 `VALIDATION_ERROR`.
7. Emit one INFO `simulation.reset.completed` event after a successful reset. It may include the request ID and event name only; do not log account values, transaction counts, idempotency keys, or request data.

### Acceptance criteria

- Reset restores exactly the three seed accounts and their exact balances.
- Reset removes all transfer/reversal history.
- Reset removes all idempotency entries; a key used before reset can produce a new transfer afterward.
- An old transaction ID returns 404 after reset.
- Reset response is 204 and has zero response bytes.
- Concurrent mutating operations are ordered entirely before or after reset by the shared lock; reset cannot expose partial state.
- A successful reset emits one sanitized completion event after its state transition completes.

### Minimum automated verification

- Direct service test: mutate state with a transfer/reversal, reset, then assert seed accounts, zero history, and cleared idempotency entry behavior.
- MVC test: reset returns 204 with empty body.
- MVC test: nonempty reset body returns 400 `VALIDATION_ERROR`.

### Manual check

- Run reset before a manual test sequence, create a transfer, reset again, and confirm the transaction lookup is 404.

## Story 13 — Protect concurrency invariants

**User story:** As a load-test author, I want concurrent requests to preserve balances and idempotency so that JMeter can safely demonstrate real contention instead of race conditions.

### Implementation tasks

1. Review every repository/service path and confirm it uses the one shared lock coordinator:
   - account list/detail/balance: read lock while copying;
   - transaction lookup/history snapshot: read lock while copying;
   - transfer, reversal, reset: write lock for their full state transition.
2. Verify no controller accesses repository maps, no mutable reference escapes, and no code uses account-specific locks alongside the shared lock.
3. Verify transfer idempotency lookup, new-transfer validation, account mutation, transaction storage, and idempotency storage all occur under one write-lock acquisition.
4. Verify reversal checks and all its mutations use one write-lock acquisition.
5. Verify reset uses the same write lock and never replaces its coordinator.
6. Keep critical sections free of HTTP calls, file operations, random waits, JSON serialization, test synchronization waits, and domain logging. Request-completion and reset events must be emitted only after the state lock has been released.
7. Do not add artificial delay/failure behavior merely to make a race easier to observe.

### Acceptance criteria

- Concurrent overspending attempts cannot create a negative balance.
- Concurrent same-key matching requests create exactly one transaction and one movement.
- Simultaneous reversal attempts produce exactly one successful reversal.
- Opposite-direction transfers complete without deadlock.
- A coherent account-list snapshot always has a total of 115000.00 and no negative balance.
- Tests use bounded timeouts and finish reliably; they do not depend on `Thread.sleep`.

### Minimum automated verification

Use a small fixed executor, a start barrier/latch, a bounded future timeout, and deterministic requests. Collect failures from all worker tasks and always terminate the executor.

1. **Overspending test:** after reset, submit 200 concurrent unique-key transfers of 100.00 from ACC-001 to ACC-002. Assert exactly 100 successes, 100 insufficient-funds outcomes, source 0.00, destination 15000.00, total 115000.00, and 100 stored transfers.
2. **Same-key test:** submit a modest number of simultaneous identical requests with one key. Assert exactly one create outcome, all remaining calls are matching replays, one transaction, and one net movement.
3. **Double-reversal test:** create one transfer, then submit two simultaneous reversals. Assert one success, one already-reversed conflict, two total transaction records, and restored balances.
4. **Opposite-direction test:** submit a bounded set of distinct-key transfers in both directions. Assert all tasks complete before the timeout, every balance is nonnegative, and total is conserved. Do not assert a timing benchmark.

### Manual/JMeter check

- Run the shared-source contention scenario from the design document. Stop worker threads before reading final balances/history.
- Run same-key concurrent retries. Expect one 201 and remaining 200 responses when all requests reach the application.

## Story 14 — Containerize the application

**User story:** As a tutorial user, I want to launch the API in one Docker container so that I can reproduce the environment without installing a database or Docker Compose stack.

### Implementation tasks

1. Add one multi-stage `Dockerfile`.
2. Use a pinned official Maven image with Java 25 for the build stage. Copy build descriptors/wrapper before source for dependency-layer caching.
3. Build and test/package the executable JAR in the build stage.
4. Use a pinned supported Eclipse Temurin Java 25 runtime image in the final stage; prefer a JRE variant if available and compatible.
5. Copy only the executable JAR to the final stage using a deterministic path/name.
6. Run as a non-root user and use exec-form Java entry point.
7. Document port 8080, `docker build -t ledgerflow-api:local .`, and `docker run --rm --name ledgerflow-api -p 127.0.0.1:8080:8080 ledgerflow-api:local` in the project README or a Docker section.
8. Add a focused `.dockerignore` so build context omits `target`, VCS data, editor metadata, and local result files. Do not exclude source/build descriptors needed by Docker.
9. Do not add Docker Compose, a volume, database configuration, or an image-internal persistence mechanism.
10. Keep structured application logs on standard output. Do not add a container log file, sidecar collector, or log persistence volume.

### Acceptance criteria

- Docker builds the image from a clean project checkout.
- The container starts on port 8080 and `GET /actuator/health` returns 200.
- Account list returns the three seed accounts in a fresh container.
- Stopping/removing and starting a new container returns to seed data.
- The final runtime image contains no Maven dependency cache or source tree beyond what the runtime needs.
- Application logs are visible through the container's standard output as structured JSON.

### Minimum automated verification

- No unit test is necessary for Dockerfile instructions.
- Build the image once and call health plus account list manually as the verification step.

## Story 15 — Run the JMeter tutorial acceptance pass

**User story:** As the tutorial author, I want a verified sequence of JMeter scenarios so that learners can practice each important REST and concurrency behavior against the completed API.

### Preparation tasks

1. Start the application locally or in Docker, then call health.
2. Set JMeter HTTP Request Defaults for localhost and port 8080.
3. Add a Header Manager for `Accept: application/json`; set `Content-Type: application/json` only on JSON-body requests.
4. Use a setUp Thread Group to reset once before workers begin. Do not reset from each worker thread.
5. Use a tearDown Thread Group only after workload threads are complete if a post-suite reset is desired.
6. Generate and retain one idempotency key per logical transfer. For a retry, reuse the same key.
7. Add an `X-Request-Id` header per logical request flow and retain it while correlating a request with its console log event. Do not use an idempotency key as a log correlation ID.

### Acceptance scenarios

1. **Health and seed read:** health returns UP; account list returns three known accounts; `ACC-001` balance is 10000.00.
2. **Create and correlate:** POST 150.75 from ACC-001 to ACC-002 with a fresh key; assert 201/COMPLETED; extract `$.transactionId` to `transactionId`.
3. **Retrieve and balances:** GET `${transactionId}`; assert same ID. Read both balances and assert 9849.25 / 5150.75 in the isolated sequence.
4. **Reverse and reconcile:** POST reversal with no body; assert 200/REVERSAL; retrieve original and assert REVERSED; validate seed balances restored and history total 2.
5. **CSV data:** use columns `sourceAccountId,destinationAccountId,amount,description`; run valid rows with a fresh key per logical row; assert response/codes by row.
6. **Idempotency:** repeat one identical request/key; assert one 201, later 200s, same transaction ID, and one net movement. Change amount with same key and assert 409.
7. **Expected errors:** test missing key, malformed JSON, unknown account, same account, and insufficient funds. Assert both HTTP status and error `code`; then verify state did not change.
8. **Contention:** reset, launch 200 concurrent unique-key transfers of 100.00 ACC-001→ACC-002 with a Synchronizing Timer; after workers finish, assert 100 successes/100 422s and final balances 0.00/15000.00.
9. **Opposite directions:** coordinate requests in both directions; verify all complete and final total is conserved.
10. **Double reversal:** coordinate two reversals for one transfer; assert one 200 and one 409.
11. **Reset:** reset after a workload and assert seed accounts, zero history, and 404 for a former transaction ID.
12. **Log correlation:** send a known `X-Request-Id` with a business request; assert it is returned and locate the corresponding sanitized completion event in console output.

### JMeter reporting expectations

- Separate expected 4xx business outcomes from unexpected 5xx/server failures in reports.
- For load runs, use non-GUI mode and lightweight listeners. Retain result files for analysis.
- Assert numeric monetary values using decimal arithmetic or integer cents, never binary floating-point logic.
- During concurrent load, inspect final balances/history after workers stop; individual in-flight request observations are timing-dependent.

### Completion criteria

- Every scenario above produces the specified behavior against one application instance.
- No 5xx response occurs during normal valid/expected-invalid scenarios.
- The final documented run notes Java version, Boot patch version, JMeter version, host resources, thread counts, ramp-up, duration, and result summary. This makes tutorial measurements reproducible without presenting them as universal performance claims.

## Suggested implementation checkpoints

Use these checkpoints to keep the project easy to recover and review.

| Checkpoint | Stories complete | What to demonstrate |
| --- | --- | --- |
| A | 1–4 | Application starts, health is UP, logging is correlated, and seed state exists internally |
| B | 5–6 | Account reads and consistent API error JSON |
| C | 7–9 | Atomic transfer endpoint and idempotent retry behavior |
| D | 10–12 | Full functional API: history, reversal, reset |
| E | 13 | Concurrent invariants protected by automated regression tests |
| F | 14–15 | Container execution and verified JMeter tutorial scenarios |

## Final implementation review

Before treating the API as ready for the tutorial, review the following:

- All monetary types are BigDecimal and request amounts never pass through float/double.
- No external data/service dependency has entered the POM, code, or Docker image.
- The one shared fair read/write lock protects all state paths, including reset and idempotency.
- The source account cannot become negative, and account total remains 115000.00.
- Transfer retry semantics and post-reversal replay semantics match the design document.
- HTTP error status, body fields, and stable error codes match the documented contract.
- Logs are structured JSON on stdout, use request IDs for correlation, and exclude financial, personal, credential, and idempotency data.
- Reset clears accounts to seed values, transfers, and idempotency keys.
- JMeter tests use a single target instance and reset only at controlled suite boundaries.
- Docker starts the same in-memory application and loses state when replaced/restarted.
