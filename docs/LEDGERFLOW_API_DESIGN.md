# LedgerFlow API — Functional and Technical Design

Status: proposed implementation baseline. Language: English. This document specifies behavior and an implementation roadmap; it contains no application implementation or build configuration.

## 1. Application overview and objective

LedgerFlow API is a small, synchronous REST API simulating USD transfers between fictional U.S. accounts. Its purpose is to support a hands-on JMeter tutorial covering functional assertions, correlation, data-driven requests, load, concurrency, idempotency, reversals, and error handling.

Keep the name **LedgerFlow API**: it describes the exercise well. It is a simulation, not a banking ledger suitable for real money.

Run one application process with Java and Maven, or one Docker container. All state exists in process memory and disappears on restart. No database, additional service, or Docker Compose is required.

### Stack and existing project

| Component | Decision |
| --- | --- |
| Java | Java 25 LTS; compile with release 25 |
| Framework | Pin an available stable Spring Boot 4.1.x patch during implementation |
| Build | Maven; prefer a pinned Maven Wrapper for repeatable local builds |
| HTTP | Spring MVC / Spring Web, embedded servlet server |
| Validation | Jakarta Bean Validation through the Boot validation starter |
| JSON | Boot-managed Jackson; monetary DTO fields use BigDecimal |
| Operations | Spring Boot Actuator, expose health only |
| Storage | In-memory repositories using maps, with coordinated locking |
| Packaging | Executable JAR; one multi-stage Dockerfile |

The supplied `pom.xml` has coordinates `org.vass:LedgerFlowAPI:1.0-SNAPSHOT` and Java source/target 25. It does not yet declare Spring Boot, dependencies, or executable-JAR packaging configuration. Leave it unchanged during documentation; add these in roadmap step 1.

Spring Boot's published 4.1 documentation supports Java 25 and Maven 3.6.3 or later. Select and pin the exact patch and Maven version when implementation begins. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).

Use the Boot 4 MVC starter, `spring-boot-starter-webmvc`, plus validation and actuator starters. Let Boot manage JSON dependency versions. Jackson 3 is Boot 4's default; do not copy older Jackson 2 customization imports without checking compatibility. [Spring MVC documentation](https://docs.spring.io/spring-boot/reference/web/servlet.html), [Boot JSON documentation](https://docs.spring.io/spring-boot/reference/features/json.html).

## 2. Scope and out-of-scope items

### In scope

- Three seeded accounts, account listing, account details, and balances.
- Immediate transfers, transaction lookup, paginated history, and compensating reversals.
- Successful-transfer idempotency, concurrency protection, and predictable JSON errors.
- Administrative reset, health check, simple unit tests, and container execution guidance.

### Out of scope

- Account creation/deletion, deposits, withdrawals, fees, interest, overdrafts, and FX.
- Authentication, authorization, customer onboarding, payment networks, settlement, and compliance.
- Persistence, recovery, distributed coordination, replication, asynchronous jobs, or message queues.
- JPA, Hibernate, Spring Data, H2, PostgreSQL, MySQL, Redis, Kafka, or external services.
- A production double-entry ledger, audit retention, browser UI, generated clients, or generated JMeter plans.

The reset endpoint is deliberately unauthenticated for local tutorials. Bind published Docker ports to localhost by default. A shared/public deployment needs a separate security decision before use.

## 3. Proposed architecture

Use a single Spring Boot application with the flow:

**HTTP controller → application service → in-memory repositories**

Controllers bind requests and select HTTP responses. Services own business rules and the complete operation boundary. Repositories store and retrieve values; they do not independently coordinate multi-repository transactions. DTOs define the API contract separately from domain objects. A central exception handler translates expected failures into JSON errors.

A shared in-memory state coordinator owns the lock used by all services. Accounts, transactions, and idempotency entries belong to the same logical state. Expose no mutable repository maps to controllers or callers.

Use concrete repository classes. Repository interfaces, service interfaces, generic base classes, mapping frameworks, CQRS, and event sourcing add little value here. Constructor injection and an injectable UTC clock are sufficient for simple tests.

**Primary locking decision:** one shared `ReentrantReadWriteLock`. Every state-changing operation takes its write lock; every state query takes its read lock and produces an immutable snapshot before releasing it. Section 8 explains the tradeoff and optional per-account alternative.

## 4. Package structure

Recommended base package: `org.vass.ledgerflow`. These are planned responsibilities, not files to generate now.

| Package | Responsibilities |
| --- | --- |
| Root package | Spring Boot application entry point |
| `controller/` | Account, transfer, and admin HTTP controllers |
| `service/` | Account queries, transfer/reversal orchestration, reset orchestration |
| `repository/` | In-memory account, transaction, and idempotency repositories; shared state coordinator |
| `domain/` | Account, transaction, transaction type/status, canonical request, idempotency entry |
| `dto/` | Transfer request; account, balance, transaction, history, and error responses |
| `exception/` | Focused application exceptions and central HTTP exception handler |
| `config/` | Clock, seed initialization, JSON strictness, and health exposure configuration |

Use separate account and transfer services if that improves readability. Avoid one class per trivial action. The reset service must use the same state coordinator as transfer and query services.

## 5. Domain model

### Account

| Field | Type / meaning |
| --- | --- |
| `accountId` | Immutable, case-sensitive string identifier |
| `holderName` | Immutable display name |
| `balance` | BigDecimal, nonnegative, normalized to two decimal places |
| `currency` | Immutable `USD` |

Prefer immutable account values replaced in the repository while holding the write lock. Never serialize a live mutable account reference.

### Transaction

| Field | Type / meaning |
| --- | --- |
| `transactionId` | Application-generated UUID string |
| `type` | `TRANSFER` or `REVERSAL` |
| `status` | `COMPLETED` or `REVERSED` |
| `sourceAccountId` | Account debited by this transaction |
| `destinationAccountId` | Account credited by this transaction |
| `amount` | Positive BigDecimal, normalized to two decimal places |
| `currency` | `USD` |
| `description` | Optional text, represented as null when absent |
| `createdAt` | UTC instant serialized in ISO 8601 with `Z` |
| `idempotencyKey` | Original transfer key; null for reversal transactions |
| `originalTransactionId` | Original transfer ID on a reversal; otherwise null |
| `reversalTransactionId` | Reversal ID on a reversed original; otherwise null |
| `reversedAt` | Reversal instant on a reversed original; otherwise null |

Transaction values are immutable snapshots. Reversal replaces the original record with a new snapshot retaining its original ID and creation time, and adds a separate reversal record.

### Idempotency entry

Store the exact key, a canonical request value, and the immutable original success-response snapshot. The canonical value consists of source ID, destination ID, normalized amount, and normalized description. Use structural equality rather than a hash alone, avoiding collision and serialization-order issues.

The entry exists only after successful completion and lasts until reset/restart. No TTL or background eviction is needed.

## 6. Transfer lifecycle and states

| Operation | Before | After |
| --- | --- | --- |
| Successful transfer | No transaction | `TRANSFER / COMPLETED` |
| Rejected transfer | No transaction | No transaction; return error |
| Successful reversal | Original `TRANSFER / COMPLETED` | Original becomes `TRANSFER / REVERSED`; new `REVERSAL / COMPLETED` |
| Rejected reversal | Existing state | No changes |

Do not include `PENDING`: execution is synchronous. Do not include `FAILED` in history for the initial version. Rejected requests did not move money, and retaining them would complicate transaction identity, retry semantics, memory use, and reversal rules. JMeter results and application logs capture failed attempts. Failed-attempt retention can be a later, separate exercise.

Only a completed original transfer is reversible. A reversal is terminal and cannot itself be reversed. If a future version introduces failed or pending records, they must also remain non-reversible.

## 7. Detailed business rules

### Request and monetary validation

1. Require source ID, destination ID, and numeric `amount`. IDs are case-sensitive, untrimmed, nonblank strings of at most 64 characters without whitespace. A syntactically valid unknown ID returns 404.
2. Require a JSON object and reject unknown properties, duplicate object keys, incompatible field types, numeric strings for amount, missing required fields, and malformed JSON with 400. Do not rely on permissive Jackson coercion defaults.
3. Amount must be strictly positive. Zero or negative numeric amounts return 422. Missing/null/nonnumeric amounts return 400.
4. Accept at most two meaningful fractional digits without rounding. `150.750` is equivalent to `150.75`; `150.751` returns 400. Normalize accepted values to scale 2 using exact conversion.
5. Limit each requested amount to `999999999999.99` to keep the educational contract bounded; larger values return 400. This is an input limit, not a floating-point implementation shortcut.
6. Use BigDecimal throughout parsing, storage, arithmetic, and assertions. Never construct monetary values through float/double. Serialize JSON numbers in plain decimal notation; examples show two places, but clients compare numeric values rather than formatting.
7. Description is optional, null or a string of at most 140 characters. Missing and explicit null are equivalent. Empty string, whitespace, and casing are preserved and significant for idempotency. Do not trim descriptions silently.
8. Currency is always USD and is server-controlled. Sending a currency field in the create request is an unknown-property error.

### Movement rules

9. Both accounts must exist. Check source first, then destination for deterministic errors when both are missing.
10. Existing source and destination must be different; otherwise return 422.
11. Check available source funds inside the write lock. Insufficient funds returns 422 without a debit, credit, history entry, or idempotency entry.
12. On success, debit the source and credit the destination by the same exact amount. Neither account may become negative.
13. Generate transaction IDs in the application; IDs must not be reused within a state epoch. Use UUIDs so IDs also remain practically distinct across resets.
14. Completed transfer history and idempotency state become visible together with both balance changes.
15. Successful operations preserve the total balance across all accounts: **115000.00 USD**. Reversals preserve this total too.

### Reversal rules

16. Reversal is a new compensating movement, not deletion or restoration of saved account snapshots.
17. Debit the original destination and credit the original source for the original amount. This preserves unrelated intervening transfers.
18. If the original destination has since spent the funds and cannot cover the reversal, return 422 `INSUFFICIENT_REVERSAL_FUNDS`. Leave the original completed and allow a later retry if funds become available. Never force a negative balance.
19. Once reversed, the original cannot be reversed again: return 409 `TRANSFER_ALREADY_REVERSED`.
20. Attempting to reverse a reversal returns 422 `TRANSACTION_NOT_REVERSIBLE`. An unknown transaction returns 404.

## 8. Concurrency and idempotency strategy

### Shared lock and atomic visibility

Use one fair `ReentrantReadWriteLock` shared across the entire in-memory store. Fairness helps limit starvation during mixed tutorial workloads, at some throughput cost. Concurrent read-only requests can proceed together. Writes are serialized, even when they involve different accounts.

This is intentional for three accounts: it is easier to explain and test, and it makes transfers, reversals, history publication, idempotency, and reset share one clear consistency boundary. A ConcurrentHashMap alone only protects individual map operations; it does not make debit plus credit atomic.

Transfer flow:

1. Parse JSON, validate header/field format, and form the canonical request without reading mutable state.
2. Acquire the shared write lock. Do not first acquire a read lock and attempt a lock upgrade.
3. Look up the idempotency key. If present, compare canonical values: return the saved success snapshot on equality, or 409 on mismatch. Do this before rechecking current funds or current transaction status.
4. For a new key, validate positivity, account existence, different accounts, and sufficient funds against the current state.
5. Calculate both resulting balances and prepare the transaction, response snapshot, and idempotency entry before changing maps.
6. Publish the two account values, transaction, and idempotency entry while still holding the write lock. No business validation or external calls occur midway through these mutations.
7. Release the lock in a finally block, then serialize the immutable response and send it over HTTP.

Reversal flow takes the same write lock, validates the current original record and funds, prepares the two account values plus original/reversal records, and publishes all changes before releasing the lock. Two simultaneous reversals result in one 200 and one 409, with exactly one compensating movement.

All account and history reads take the read lock while copying the requested state into immutable DTO snapshots. The accounts list therefore shows a coherent total; it cannot expose a debit without its corresponding credit. Release the lock before JSON serialization. Two separate HTTP balance queries are separate snapshots and can see intervening operations.

Reset takes the same write lock. Keep the lock object itself stable across resets. Never clear maps outside this lock or replace the lock during reset.

There is no cycle of account locks, so opposite transfers cannot deadlock. Avoid nested independent locks, read-to-write upgrades, HTTP calls, logging I/O, or other blocking work in the critical section. Large history reads should copy a consistent snapshot under the read lock and sort/page that immutable snapshot afterward.

The lock provides atomic visibility and business-rule safety within one live JVM. It does not provide database-style crash recovery or rollback after JVM-fatal failures such as out-of-memory errors. Do not claim durability.

### Idempotency contract

- `POST /transfers` requires exactly one `Idempotency-Key` header value containing 1–128 characters from letters, digits, `.`, `_`, `:`, and `-`. UUIDs are recommended; arbitrary strings matching this syntax are accepted. Missing, repeated, blank, or invalid values return 400.
- Keys are case-sensitive and globally scoped to transfer creation within the current process state epoch. Header names remain case-insensitive under HTTP.
- The first successful request returns 201. Later requests with the same canonical payload return 200, the same transaction ID, and the original success-response body. Both include the original resource Location.
- JSON property order and equivalent monetary forms such as `150.75` and `150.750` do not change identity. Missing description and null are equivalent; any other description change is a different payload.
- A valid request reusing a committed key with a different canonical payload returns 409, even if its changed accounts or funds would fail business validation. Malformed input still returns 400 before lookup.
- Concurrent same-key requests wait on the write lock. Exactly one successful operation is committed; matching followers replay it. No separate key lock, future, reservation, or map compute callback is required.
- Validation/business failures do not reserve keys or cache errors. A retry can succeed after balances change. This is success-only idempotency, not caching of every attempted response.
- If the client times out after commit, retrying the same key recovers the original success without moving money again.
- After reversal, replay still returns the original `COMPLETED` creation snapshot. `GET /transfers/{id}` returns the current `REVERSED` representation. This distinction preserves the original-response contract without reapplying the transfer.
- Reset/restart clears keys. Reusing a pre-reset key can create a new transfer. Use fresh keys per test run and never assume deduplication survives reset.

### Why not per-account locks initially?

One ReentrantLock per account is a valid later concurrency lesson, but it is not necessary for this baseline. A correct alternative acquires account locks in lexicographic ID order, regardless of movement direction, and releases them in reverse order. Same-key coordination must precede account locking; reset needs a global lifecycle barrier; transaction publication and history reads also need coherent snapshot coordination. Every path must obey the same lock hierarchy. Account locks alone are insufficient for the full API contract.

Do not mix this alternative with the baseline. Benchmark the shared-lock version first; move to finer locking only if teaching that additional complexity is an explicit goal.

## 9. Complete endpoint specifications

Base URL: `http://localhost:8080`. API prefix: `/api/v1`. Responses with bodies use `application/json`. Transfer creation requires `Content-Type: application/json`. Clients should request `Accept: application/json`. Successful response examples are in section 10; all business API errors use section 11.

Return `Cache-Control: no-store` for business API responses so tutorial requests observe server state. No authentication is required in the local baseline.

| Method and path | Request | Success | Defined errors |
| --- | --- | --- | --- |
| `GET /api/v1/accounts` | No body or query parameters | 200, array of all accounts sorted by accountId ascending | Common HTTP errors only |
| `GET /api/v1/accounts/{accountId}` | Account ID path segment | 200, account object | 400 invalid ID format; 404 missing account |
| `GET /api/v1/accounts/{accountId}/balance` | Account ID path segment | 200, accountId/balance/currency object | 400 invalid ID format; 404 missing account |
| `POST /api/v1/transfers` | Required idempotency header and transfer body | 201 new transfer; 200 matching replay; Location points to transaction resource | 400 format/header; 404 account; 409 key conflict; 422 business rule |
| `GET /api/v1/transfers/{transactionId}` | UUID path segment | 200, current transaction object | 400 malformed UUID; 404 unknown UUID |
| `GET /api/v1/transfers` | Optional accountId, offset, limit | 200, history envelope | 400 invalid parameters; 404 unknown account filter |
| `POST /api/v1/transfers/{transactionId}/reversal` | UUID path segment; empty body | 200, newly created reversal transaction; Location points to reversal resource | 400 malformed UUID/nonempty body; 404 transaction; 409 already reversed; 422 not reversible/insufficient reversal funds |
| `POST /api/v1/admin/reset` | Empty body | 204, no body and no Location | 400 nonempty body |
| `GET /actuator/health` | No body | 200, Actuator health JSON | 503 when health reports DOWN/OUT_OF_SERVICE |

Unknown query parameters on business endpoints return 400 to expose tutorial typos. GET requests have no defined body; do not send one. For bodyless POSTs, send zero body bytes, not `{}`. The reversal endpoint has no idempotency-key requirement or replay behavior; an incidental key header does not change its one-reversal rule.

### History query contract

- `accountId`: optional exact account ID; include transactions where it is either source or destination. Unknown account returns 404; omitted means all accounts.
- `offset`: integer, default 0, minimum 0.
- `limit`: integer, default 50, range 1–200.
- Sort by `createdAt` ascending, then `transactionId` ascending for timestamp ties. This tie-breaker is deterministic, not a claim of commit order.
- Return both original transfers and reversal transactions. Originals reflect their current status.
- `total` counts all matching records before pagination. Offset beyond the end returns an empty items array with the same total.
- Filters, total, and items are derived from one snapshot. Separate pages are not a cross-request snapshot during concurrent writes or reset; inspect history after a load phase when exact reconciliation is needed.

### Reversal retry behavior

A successful reversal returns 200 by design, although it creates a resource. If the response is lost, a retry returns 409. Retrieve the original transaction, read its `reversalTransactionId`, then fetch that resource to confirm the outcome. This keeps the minimal reversal contract deterministic without adding a second idempotency scheme.

## 10. JSON request and response examples

Examples use illustrative server-generated UUIDs and UTC timestamps. The sequence assumes a fresh reset and no concurrent activity.

### List accounts — 200

```json
[
  {"accountId":"ACC-001","holderName":"Alice Johnson","balance":10000.00,"currency":"USD"},
  {"accountId":"ACC-002","holderName":"Brian Miller","balance":5000.00,"currency":"USD"},
  {"accountId":"ACC-003","holderName":"Acme Services LLC","balance":100000.00,"currency":"USD"}
]
```

### Retrieve account — 200

```json
{
  "accountId": "ACC-001",
  "holderName": "Alice Johnson",
  "balance": 10000.00,
  "currency": "USD"
}
```

### Retrieve balance — 200

```json
{"accountId":"ACC-001","balance":10000.00,"currency":"USD"}
```

### Create transfer

Send `POST /api/v1/transfers`, `Content-Type: application/json`, and `Idempotency-Key: tutorial-001` with:

```json
{
  "sourceAccountId": "ACC-001",
  "destinationAccountId": "ACC-002",
  "amount": 150.75,
  "description": "Test payment"
}
```

Response: **201 Created**, `Location: /api/v1/transfers/7f8a12c0-41e3-4d7b-9c60-123456789001`.

```json
{
  "transactionId": "7f8a12c0-41e3-4d7b-9c60-123456789001",
  "type": "TRANSFER",
  "status": "COMPLETED",
  "sourceAccountId": "ACC-001",
  "destinationAccountId": "ACC-002",
  "amount": 150.75,
  "currency": "USD",
  "description": "Test payment",
  "createdAt": "2026-11-08T15:00:00Z",
  "idempotencyKey": "tutorial-001",
  "originalTransactionId": null,
  "reversalTransactionId": null,
  "reversedAt": null
}
```

GET of that transaction initially returns the same body with 200. A matching idempotent POST returns the same body and Location with **200**, including after the original is reversed.

Balances after creation:

```json
{"accountId":"ACC-001","balance":9849.25,"currency":"USD"}
```

```json
{"accountId":"ACC-002","balance":5150.75,"currency":"USD"}
```

### History — 200

`GET /api/v1/transfers?accountId=ACC-001&offset=0&limit=50` before reversal:

```json
{
  "items": [
    {
      "transactionId": "7f8a12c0-41e3-4d7b-9c60-123456789001",
      "type": "TRANSFER",
      "status": "COMPLETED",
      "sourceAccountId": "ACC-001",
      "destinationAccountId": "ACC-002",
      "amount": 150.75,
      "currency": "USD",
      "description": "Test payment",
      "createdAt": "2026-11-08T15:00:00Z",
      "idempotencyKey": "tutorial-001",
      "originalTransactionId": null,
      "reversalTransactionId": null,
      "reversedAt": null
    }
  ],
  "offset": 0,
  "limit": 50,
  "total": 1
}
```

### Reverse — 200

Send `POST /api/v1/transfers/7f8a12c0-41e3-4d7b-9c60-123456789001/reversal` with no body. Response Location: `/api/v1/transfers/7f8a12c0-41e3-4d7b-9c60-123456789002`.

```json
{
  "transactionId": "7f8a12c0-41e3-4d7b-9c60-123456789002",
  "type": "REVERSAL",
  "status": "COMPLETED",
  "sourceAccountId": "ACC-002",
  "destinationAccountId": "ACC-001",
  "amount": 150.75,
  "currency": "USD",
  "description": "Reversal of 7f8a12c0-41e3-4d7b-9c60-123456789001",
  "createdAt": "2026-11-08T15:01:00Z",
  "idempotencyKey": null,
  "originalTransactionId": "7f8a12c0-41e3-4d7b-9c60-123456789001",
  "reversalTransactionId": null,
  "reversedAt": null
}
```

GET of the original now returns:

```json
{
  "transactionId": "7f8a12c0-41e3-4d7b-9c60-123456789001",
  "type": "TRANSFER",
  "status": "REVERSED",
  "sourceAccountId": "ACC-001",
  "destinationAccountId": "ACC-002",
  "amount": 150.75,
  "currency": "USD",
  "description": "Test payment",
  "createdAt": "2026-11-08T15:00:00Z",
  "idempotencyKey": "tutorial-001",
  "originalTransactionId": null,
  "reversalTransactionId": "7f8a12c0-41e3-4d7b-9c60-123456789002",
  "reversedAt": "2026-11-08T15:01:00Z"
}
```

Balances are again 10000.00 and 5000.00. History contains two records. With intervening transfers, only this transfer's effect is undone; account balances need not equal the seed balances.

### Reset and health

Reset returns **204 No Content** with zero response body bytes. Following reset, history returns:

```json
{"items":[],"offset":0,"limit":50,"total":0}
```

Healthy Actuator response, **200**:

```json
{"status":"UP"}
```

Actuator keeps its native health format, including a native `{"status":"DOWN"}` response with 503 when applicable. Do not wrap it in the business API error format. [Actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html).

## 11. Error-handling standard

Every application-handled `/api/v1` error has `timestamp` (UTC), `status` (HTTP integer), `error` (stable HTTP label), `code` (stable application code), `message` (readable explanation), and `path` (request path without query). An optional `details` array contains `field` and `message` entries for invalid input. Omit details when not applicable.

Use a central handler and consistent fallback handling for framework errors under the API prefix. Do not expose stack traces or implementation class names. HTTP status is authoritative; clients should assert `code`, not exact prose. Low-level requests rejected before Spring handles them are outside this JSON guarantee.

| HTTP | Code | Trigger |
| --- | --- | --- |
| 400 | `MALFORMED_JSON` | Invalid JSON or duplicate object keys |
| 400 | `VALIDATION_ERROR` | Missing/unknown fields, invalid types/precision/size, invalid path/query, unexpected body |
| 400 | `INVALID_IDEMPOTENCY_KEY` | Missing or invalid create-transfer key |
| 404 | `ACCOUNT_NOT_FOUND` | Source, destination, queried account, or filter account absent |
| 404 | `TRANSACTION_NOT_FOUND` | Valid UUID not present |
| 404 | `RESOURCE_NOT_FOUND` | Unknown API route |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | Committed key with different payload |
| 409 | `TRANSFER_ALREADY_REVERSED` | Original has already been reversed |
| 422 | `INVALID_AMOUNT` | Amount zero or negative |
| 422 | `SAME_ACCOUNT_TRANSFER` | Source equals destination |
| 422 | `INSUFFICIENT_FUNDS` | Source cannot cover transfer |
| 422 | `INSUFFICIENT_REVERSAL_FUNDS` | Original destination cannot cover reversal |
| 422 | `TRANSACTION_NOT_REVERSIBLE` | Target is not a completed original transfer |
| 405 | `METHOD_NOT_ALLOWED` | Unsupported method; include standard Allow header |
| 406 | `NOT_ACCEPTABLE` | Client does not accept JSON |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Transfer request is not application/json |
| 500 | `INTERNAL_ERROR` | Unexpected server failure; log server-side details |

For 406, the server may supply its JSON error even though the client cannot consume it; the status remains the primary signal.

Malformed JSON example — **400**:

```json
{
  "timestamp":"2026-11-08T15:02:00Z",
  "status":400,
  "error":"Bad Request",
  "code":"MALFORMED_JSON",
  "message":"Request body must contain a valid JSON object.",
  "path":"/api/v1/transfers"
}
```

Invalid precision example — **400**:

```json
{
  "timestamp":"2026-11-08T15:02:00Z",
  "status":400,
  "error":"Bad Request",
  "code":"VALIDATION_ERROR",
  "message":"Request validation failed.",
  "path":"/api/v1/transfers",
  "details":[{"field":"amount","message":"Amount must be exactly representable with two decimal places."}]
}
```

Unknown account example — **404**:

```json
{
  "timestamp":"2026-11-08T15:02:00Z",
  "status":404,
  "error":"Not Found",
  "code":"ACCOUNT_NOT_FOUND",
  "message":"Account ACC-999 was not found.",
  "path":"/api/v1/accounts/ACC-999"
}
```

Same key, changed amount example — **409**:

```json
{
  "timestamp":"2026-11-08T15:02:00Z",
  "status":409,
  "error":"Conflict",
  "code":"IDEMPOTENCY_KEY_CONFLICT",
  "message":"Idempotency key is already associated with a different transfer request.",
  "path":"/api/v1/transfers"
}
```

Insufficient funds example — **422**:

```json
{
  "timestamp":"2026-11-08T15:02:00Z",
  "status":422,
  "error":"Unprocessable Entity",
  "code":"INSUFFICIENT_FUNDS",
  "message":"Source account has insufficient funds.",
  "path":"/api/v1/transfers"
}
```

Repeated reversal example — **409**:

```json
{
  "timestamp":"2026-11-08T15:02:00Z",
  "status":409,
  "error":"Conflict",
  "code":"TRANSFER_ALREADY_REVERSED",
  "message":"Transfer has already been reversed.",
  "path":"/api/v1/transfers/7f8a12c0-41e3-4d7b-9c60-123456789001/reversal"
}
```

Validation precedence: HTTP decoding and format/header validation first; committed-key replay/conflict second; new-operation business validation third. For multiple field-format errors, collect details in stable field order. For new transfers, validate positive amount, source existence, destination existence, same-account rule, then funds. For reversals, validate existence, type, already-reversed status, remaining eligibility, then funds.

## 12. In-memory data and reset strategy

Seed exactly these accounts before accepting application traffic:

| accountId | holderName | balance | currency |
| --- | --- | ---: | --- |
| ACC-001 | Alice Johnson | 10000.00 | USD |
| ACC-002 | Brian Miller | 5000.00 | USD |
| ACC-003 | Acme Services LLC | 100000.00 | USD |

Keep three private maps: accounts by account ID, transactions by transaction ID, and idempotency entries by key. ConcurrentHashMap is acceptable, but all access still follows the shared-lock protocol. Construct seed amounts from exact decimal values.

Startup and reset use the same seed factory. Under the write lock, reset replaces the account values and clears transaction and idempotency maps as one logical operation. Return 204 only after completion. Existing IDs become 404 and old keys become reusable.

A concurrent operation is ordered before or after reset by lock acquisition. A request that arrived before reset may acquire the lock afterward and operate on the new state. Also, a response prepared before reset may arrive after reset has completed. Reset does not cancel requests or establish a barrier for traffic still in flight.

For deterministic suites, stop workload threads, wait for outstanding requests, reset once, verify seeds and empty history, then start the next workload. Never reset from every load-test thread.

No persistence files, scheduled snapshots, database drivers, or shutdown-save behavior. No automatic history/key eviction: evicting keys could permit duplicate debits. Memory grows with successful transfers; pagination limits response size, not retained memory. Reset between bounded test runs.

## 13. Non-functional requirements

| Area | Requirement / acceptance criterion |
| --- | --- |
| Correctness | No negative balances; total always 115000.00; rejected operations leave state unchanged |
| Atomicity | A query never observes a partial movement or partially published operation |
| Repeatability | Startup/reset produces identical accounts and empty history/key stores |
| Runtime | One JVM or one container, port 8080, no service dependencies |
| Precision | Exact decimal arithmetic and numeric JSON assertions; no float/double money |
| Observability | Health endpoint plus concise logs; no request-body logging by default |
| Performance | Measure latency, throughput, error rate, CPU, and memory; no hardware-independent throughput promise |
| Concurrency | Writes serialize; contention and queuing under load are expected teaching outcomes |
| Resource use | Bound test duration and successful-operation count; reset between runs |
| API stability | Version prefix v1; stable fields/codes and deterministic history ordering |
| Deployment | Exactly one instance for a test run; multiple replicas have independent balances and keys |
| Maintainability | Plain service tests, small DTOs, minimal dependencies, no external test infrastructure |

Use normal servlet threading initially. Virtual threads are optional experimentation, not a solution to the shared write-lock bottleneck. Do not add artificial latency or random failures to baseline behavior.

## 14. JMeter test scenarios enabled by the API

Use HTTP Request Defaults for host/port and a Header Manager for JSON requests. Generate a key once per logical transfer and retain it for retries. Generating a new key on each retry defeats the idempotency exercise.

The following are test design instructions, not a generated JMeter plan. JMeter provides JSON extraction, CSV Data Set Config, assertions, and synchronization timers for these scenarios. [JMeter component reference](https://jmeter.apache.org/usermanual/component_reference.html).

| Scenario | Procedure | Assertions |
| --- | --- | --- |
| 1. Read balance | GET ACC-001 balance after reset | 200, accountId matches, balance 10000.00, USD |
| 2. Create transfer | POST sample 150.75 request with fresh key | 201, COMPLETED, amount, source/destination, Location |
| 3. Correlate ID | JSON Extractor expression `$.transactionId`; store in `transactionId` | Extracted value is nonempty and UUID-shaped |
| 4. Retrieve created transfer | GET `/api/v1/transfers/${transactionId}` | 200, same ID and amount |
| 5. Verify balances | Read source/destination after step 2 | 9849.25 and 5150.75 in isolated sequence |
| 6. Reverse | POST `/api/v1/transfers/${transactionId}/reversal` with empty body | 200, type REVERSAL, linked original ID |
| 7. Verify restoration | GET both balances and original transfer | Seed balances restored, original REVERSED, history total 2 |
| 8. CSV-driven transfers | CSV columns sourceAccountId,destinationAccountId,amount,description | Each unique logical row execution uses a fresh key; success/error matches dataset |
| 9. Shared-source contention | After reset, send 200 concurrent unique-key transfers of 100.00 from ACC-001 to ACC-002 | Exactly 100 return 201 and 100 return 422; balances 0.00 / 15000.00; history total 100 |
| 10. Idempotent retries | Send same payload/key repeatedly, then simultaneously | One 201, remaining 200; one transaction ID and one net debit/credit |
| 11. Suite reset | setUp Thread Group resets once; tearDown resets after workers stop | 204; seeds, empty history; old transaction GET returns 404 |
| 12. Error responses | Excessive amount, missing account, same account, malformed JSON, missing key | Expected HTTP and code; no monetary/history change |
| 13. Key conflict | Create successfully, then change amount or description with same key | 409; exactly one transfer retained |
| 14. Opposite directions | Concurrent ACC-001→ACC-002 and ACC-002→ACC-001 with fresh keys | No deadlock, nonnegative balances, conserved total |
| 15. Double reversal | Two concurrent reversal requests for one completed transfer | Exactly one 200 and one 409; only one reversal record |
| 16. Spent reversal funds | Transfer 150.75 ACC-001→ACC-002; transfer all 5150.75 ACC-002→ACC-003; reverse first | 422 INSUFFICIENT_REVERSAL_FUNDS; original still COMPLETED |
| 17. Canonical payload | Replay 150.75 as 150.750 and reorder JSON properties | 200 and same original transaction |
| 18. Replay after reversal | Repeat original create request/key after reversal | 200 original COMPLETED snapshot; GET original shows REVERSED; no balance changes |

Suggested CSV content, embedded here only as documentation:

```csv
sourceAccountId,destinationAccountId,amount,description
ACC-001,ACC-002,10.00,Alice to Brian
ACC-002,ACC-003,5.25,Brian payment
ACC-003,ACC-001,25.00,Acme payment
```

Use CSV sharing mode deliberately; repeated rows are acceptable but must use distinct keys unless replay is the objective. Stop threads at EOF for finite datasets, or explicitly plan the effect of recycling. Only the three seeded account IDs exist.

Use decimal arithmetic or integer cents in assertions; avoid binary floating-point comparisons. During shared-account load, individual post-request balances are timing-dependent. Assert exact balances after workers stop; during load, use the coherent account list to assert nonnegative balances and total conservation.

The exact contention counts assume all 200 requests reach the application and complete, with no network/client timeouts. On a timeout, retry with the same key and reconcile unique committed IDs after the run rather than blindly issuing new transfers.

For deliberate 4xx scenarios, assert the expected code and handle expected errors separately from unexpected server failures in JMeter reports. Use a Synchronizing Timer or coordinated start for focused concurrency cases. Run sustained load in non-GUI mode with lightweight listeners; retain results for later analysis. [JMeter best practices](https://jmeter.apache.org/usermanual/best-practices.html).

## 15. Incremental implementation plan

Each step should leave a runnable or independently verifiable increment. The user implements these steps; no project scaffolding is included here.

| Step | Small implementation increment | Completion check |
| --- | --- | --- |
| 1 | Add pinned Boot 4.1.x, Java release 25, MVC/validation/actuator/test dependencies, Boot packaging plugin | Maven builds an executable JAR; application starts on 8080 |
| 2 | Configure UTC clock and minimal health exposure | GET health returns 200 UP without extra services |
| 3 | Define account values, seed factory, repositories, shared lock coordinator | Exactly three seeded accounts with total 115000.00 |
| 4 | Add account list/detail/balance endpoints and response DTOs | Read endpoints return correct snapshots; missing account gives 404 |
| 5 | Add central error DTO/handler and strict request validation | Malformed JSON, wrong types, and validation failures use consistent 400 JSON |
| 6 | Add transaction model and atomic transfer service under write lock | Positive transfer moves exact funds; insufficient funds changes nothing |
| 7 | Add canonical requests and success-only idempotency under the same lock | Matching replay returns 200; changed payload returns 409 without movement |
| 8 | Expose create-transfer controller and Location handling | First create returns 201 with complete response and generated UUID |
| 9 | Add transaction lookup and filtered paginated history snapshots | Lookup, filtering, stable ordering, empty pages, and totals behave as specified |
| 10 | Add reversal service and endpoint | One compensating record, original updated, repeated reversal rejected |
| 11 | Add reset using the existing lock and seed factory | Accounts, history, and keys reset together; old IDs become missing |
| 12 | Complete focused service/HTTP tests and a small concurrency regression | Critical invariants and HTTP contracts pass with no external services |
| 13 | Add the single multi-stage Dockerfile and run instructions | Image builds and serves the same API on mapped port 8080 |
| 14 | Execute manual/JMeter tutorial sequence, then bounded load | Correlation, errors, contention, replay, reversal, and reset demonstrated |

Do not add persistence or more architecture to solve issues that can be addressed within these steps.

## 16. Recommended automated testing strategy

Keep automation small. Most tests directly instantiate the service with real in-memory repositories, a fresh coordinator, and a fixed clock. No Spring context, database, Docker, Testcontainers, or mocked map internals is needed for these tests. Use Boot's managed JUnit support.

Prioritize these service tests:

1. Successful movement and conservation of total funds.
2. Insufficient funds, missing accounts, same-account transfer, and nonpositive amount leave state unchanged.
3. Same-key/same-payload replay creates one operation; changed payload conflicts; equivalent decimal amounts compare equal.
4. Reversal creates the linked compensation once; double reversal and reversal-of-reversal fail correctly.
5. Insufficient reversal funds leave the original completed and balances unchanged.
6. Reset restores seeds, removes transactions, and permits reuse of an old key.
7. Replay after reversal returns the saved creation snapshot while lookup returns current state.

Add one small executor-based concurrency test with a coordinated start and bounded timeout for overspending, plus one for simultaneous same-key requests. Assert final invariants and operation counts rather than thread order or elapsed milliseconds. Collect worker failures, avoid sleeps as synchronization, and always shut down the executor. A tiny concurrent reversal case is useful if the first two tests remain easy to maintain.

A few HTTP-focused tests cover what service tests cannot: missing header, malformed JSON, strict monetary input, 201 versus 200 replay, error schema, Location, and 204 with no body. Use the Boot 4-compatible MVC test facilities; do not reproduce every JMeter scenario as an integration test.

Manual testing with the user's chosen tool and JMeter remains the main functional/load exploration. No coverage percentage target, benchmark suite, or exhaustive concurrency proof is required for this tutorial application.

## 17. Docker and local execution guidance

This section describes the future Dockerfile; it intentionally does not provide or create one now.

### Single multi-stage Dockerfile design

1. **Build stage:** use the official Maven image with a Java 25 JDK. Choose an available exact Maven/Temurin 25 tag and pin it, optionally by digest. Copy the Maven descriptor/wrapper before source to allow dependency-layer caching, then copy sources and package/test the application.
2. **Runtime stage:** use an official Eclipse Temurin Java 25 JRE image for the target architecture, preferably a smaller supported JRE distribution variant. If that variant is unavailable, use the Java 25 JDK runtime image and accept the extra size. Verify image tags before writing the Dockerfile.
3. Copy only the executable application JAR from the build stage to a predictable runtime path. Use a deterministic artifact selection so an original/non-executable JAR cannot be copied accidentally.
4. Run as a non-root user, use an exec-form Java entry point, and document/expose port 8080. No embedded Maven, source tree, secrets, persistence volume, database configuration, or Compose file belongs in the runtime image.
5. A Docker HEALTHCHECK is optional; do not install extra tools solely to run curl. The tutorial client can call Actuator health externally.

Official image references: [Maven image](https://hub.docker.com/_/maven), [Eclipse Temurin image](https://hub.docker.com/_/eclipse-temurin). These registries provide available tags; select tags during implementation rather than assuming a floating alias is reproducible.

After implementation, from the project root:

```shell
docker build -t ledgerflow-api:local .
docker run --rm --name ledgerflow-api -p 127.0.0.1:8080:8080 ledgerflow-api:local
```

Then request `http://localhost:8080/actuator/health`. Stop the container with `docker stop ledgerflow-api`. The next run starts from seed data. No volume is required; adding a volume does not make the maps persistent.

Without Docker, Java 25 and Maven are sufficient:

```shell
mvn clean verify
mvn spring-boot:run
```

Alternatively, run the packaged executable JAR with `java -jar` and its actual path under `target/`. If a Maven Wrapper is added, use `mvnw.cmd` on Windows or `./mvnw` on Unix instead of installed Maven. These commands become applicable after roadmap step 1; the current POM is not yet a Boot application.

## 18. Open questions, risks, and technical decisions

### Decisions adopted for the baseline

| Topic | Decision and rationale |
| --- | --- |
| Application name | Keep LedgerFlow API; clear and sufficient |
| Locking | One shared read/write lock: simplest coherent transaction boundary for three accounts |
| Failed attempts | Exclude from history; errors belong in test results/logs |
| Replay after reversal | Immutable original success body; current state through GET |
| Failed-key reuse | Failures do not consume keys; only successful operations deduplicate |
| Reversal funds | Reject insufficient funds rather than violating nonnegative balances |
| Reversal retries | Second attempt returns 409; original links to the successful reversal |
| Monetary scale | Exact cents, no rounding; equivalent numeric forms canonicalize |
| History | Include reversals and current original statuses; bounded pages, unbounded retention until reset |
| Reset | Exclusive mutation; no cancellation of requests already in flight |
| Security | Local educational use; no credentials or real financial data |

### Risks and limitations

- Shared write locking limits throughput; load tests measure this design and the host, not a general banking platform.
- Memory is unbounded across successful operations until reset. Pagination does not solve retention; very long tests need an explicit size/duration budget.
- Client disconnects do not imply rollback. Transfer retries must reuse the key; reversal retries use transaction lookup to disambiguate a 409.
- Reset and restart deliberately invalidate deduplication guarantees and resource identity from previous runs.
- Multiple application instances cannot share balances or keys; distributed testing clients must all target the same instance.
- In-memory consistency is not durable financial accounting. JVM termination loses the entire state.
- Strict JSON behavior and Boot 4/Jackson 3 imports need focused verification during implementation.

### Optional follow-up questions, with defaults already chosen

- Should finer per-account locking become a separate advanced tutorial? Default: retain the shared lock for the initial series.
- Will anyone need remote access? Default: localhost only; decide exposure/security before shared deployment.
- Should reversals gain independent idempotency keys later? Default: no, use the one-reversal rule and linked-resource recovery.
- Will sustained tests exceed available memory? Default: bounded runs and explicit reset; introduce a capacity policy only if measured workloads require it.

None of these questions blocks implementing the documented baseline.
