# Instant Money Transfer - Architecture and Product Specification

## 1. Purpose

Build a secure, reliable instant-transfer feature for moving money between accounts held in the same system. The implementation is a modular monolith with a REST API and a small React web client.

The case study's priorities are correctness, consistency, security, and completing a transfer within seconds. The system must not lose or execute a transfer twice, must enforce customer limits, and must not expose another customer's data.

## 2. Scope

### Included

- Create an internal account-to-account transfer.
- Prevent duplicate execution when a request is retried.
- Enforce per-transfer, daily, and monthly limits.
- List the authenticated customer's accounts and balances.
- Return the customer's remaining daily and monthly limits.
- List and view the authenticated customer's outgoing transfers, including date filtering and pagination.
- Return the last six months of completed-transfer statistics, including months with no transfers.
- Provide a React UI for creating transfers, viewing transfer history/details, and viewing monthly statistics.
- Provide consistent API success/error models, automated tests, and a Postman collection.
- Provide a Docker-based local development environment and repeatable backend test infrastructure.

### Excluded

- Customer registration, KYC, cards, loans, and external payment networks.
- Transfers to accounts outside this system.
- Multi-currency transfers, scheduled transfers, reversals, and administrative interfaces.
- A distributed microservice architecture, message broker, or cache in the initial release.

## 3. Technology decisions

| Area | Choice | Rationale |
| --- | --- | --- |
| Backend | Java 21 and Spring Boot 3 | Mature Java ecosystem for REST APIs, transactions, validation, and security. |
| Database | PostgreSQL | Relational transactions, row-level locking, constraints, and aggregation queries support the consistency requirements. |
| Schema management | Flyway | Versioned, reviewed database changes; Hibernate must not create or update production schemas. |
| Frontend | React with TypeScript and Vite | Small, typed client suitable for the required transfer and reporting flows. |
| API contract | REST, JSON, `/api/v1` | Simple integration surface for the web client and Postman. |
| Containers | Docker, Docker Compose | Consistent local services and a reproducible way to run the application stack. |
| Tests | JUnit 5, Mockito, Spring integration tests, Testcontainers PostgreSQL, JaCoCo | Verify business rules and real PostgreSQL locking/constraint behavior; target at least 80% backend functionality coverage. |

Use the Spring Boot-managed dependency versions rather than independently pinning compatible library versions. Document exact selected versions in the project README when the implementation is scaffolded.

## 4. Architecture

### Deployment shape

Use one repository and one backend application, organized by business capability rather than deployed as services:

```text
backend/
  src/main/java/.../
    account/
    customer/
    security/
    statistics/
    transfer/
    shared/
  src/main/resources/
    db/migration/
frontend/
docs/
  postman/
```

Each backend capability owns its API/controller, application use cases, domain rules, and persistence code. Shared code is limited to cross-cutting concerns such as error handling, trace IDs, and common configuration. Keep transfer orchestration in the transfer application service; controllers should handle HTTP concerns, not money movement.

Run the backend as a single deployable Spring Boot application against PostgreSQL. In development, run the React dev server separately and proxy API requests to the backend. In production, serve the built client and API behind the same HTTPS origin where practical. Do not introduce service-to-service calls for the initial scope.

Use Docker Compose for local development. It should provide PostgreSQL and support running the backend and React frontend as services, with health checks and service startup ordered after PostgreSQL is healthy. Persist local PostgreSQL data in a named volume. Keep local-only settings in an ignored `.env` file and provide a safe `.env.example` with placeholders, never real credentials. Document how to start and stop the stack and how to reset local data.

Build the backend container from a multi-stage Dockerfile, run it as a non-root user, and keep build tools out of the runtime image. The frontend may use its own development container locally; its production build is static assets and can be served by the chosen hosting layer. Docker is a packaging/development tool here, not a reason to split the backend into services.

### Transfer transaction and concurrency

Creating a transfer is one PostgreSQL transaction. The transfer is reported as successful only after the transaction commits.

1. Authenticate the caller and resolve the customer from the trusted security principal; never accept the acting customer ID from the request body or query string.
2. Lock the sender's customer row for the duration of the transaction. This serializes that customer's concurrent transfers so daily/monthly limit checks cannot race.
3. Check the idempotency key and request fingerprint. A retry with the same key and same request returns the original transfer; reusing the key with a different request returns a conflict.
4. Resolve the source account owned by the sender and the active destination account by internal IBAN. Lock both accounts in a deterministic ID order to avoid transfer-to-transfer deadlocks.
5. Validate ownership, account state, currency, that source and destination differ, sufficient funds, and all transfer limits.
6. Insert the completed transfer and immutable debit/credit ledger entries, update both account balances, and commit all changes atomically.

Use database constraints as a final integrity boundary (including non-negative balances, positive amounts, unique idempotency keys per customer, and valid foreign keys). Store money as `NUMERIC(19,2)`/`BigDecimal`, never floating-point values. The account balance and ledger postings must be changed in the same transaction; no partial debit, credit, transfer record, or success response is permitted.

Initially a transfer has only a `COMPLETED` state: within this system, creation, both postings, and balance updates commit together. Failed validations leave no transfer or ledger entries. If a later external integration introduces pending states, the state model and accounting workflow must be revisited rather than simulating asynchronous behavior now.

## 5. Domain model

| Entity | Important fields and rules |
| --- | --- |
| `Customer` | Internal ID and stable identity-provider subject. Used to scope every customer-owned query. |
| `Account` | Internal ID, owner customer, unique internal IBAN, currency, status, and current balance. Only active TRY accounts can participate in this release. |
| `Transfer` | ID, sender customer, source and destination account, positive amount, currency, description, status, creation time, idempotency key, and request fingerprint. |
| `LedgerEntry` | Immutable posting linked to a transfer and account, with debit/credit direction and amount. Every committed transfer has one debit and one equal credit. |

Keep ledger entries append-only at the application level. A transfer's sender is the customer who initiated it; transfer history and monthly statistics in this release are outgoing transfers only. Receiving a transfer changes the recipient's account balance but does not make the transfer visible in the recipient's outgoing history.

## 6. Business rules

Default configurable limits, applied to the sending customer:

| Limit | Default |
| --- | ---: |
| Per transfer | 50,000 TRY |
| Daily total | 100,000 TRY |
| Monthly total | 500,000 TRY |

Expose these values as validated Spring configuration with environment-variable overrides. Daily totals use the business day in `Europe/Istanbul`; monthly totals use that same business timezone. Only committed `COMPLETED` outgoing transfers count. The check and transfer commit must be serialized for a customer as described above.

Amounts must be greater than zero and have at most two fractional digits. The source and destination must be distinct, active TRY accounts; the source must belong to the authenticated customer. Reject insufficient funds and limit breaches without changing balances. Unknown/ineligible destination accounts must not disclose whether an IBAN belongs to a particular customer.

The remaining limit is `max(0, configured limit - completed outgoing total in the applicable period)`. Also return the per-transfer maximum. Limit responses reflect committed transfers; there is no reservation for a transfer that has not committed.

## 7. REST API

All endpoints are under `/api/v1` and require authentication except explicitly documented health endpoints.

| Method and path | Behavior |
| --- | --- |
| `POST /transfers` | Create a transfer. Requires `Idempotency-Key`; body contains `sourceAccountId`, `receiverIban`, `amount`, and optional `description`. Returns the committed transfer. |
| `GET /transfers` | List the authenticated customer's outgoing transfers, paginated, newest first. Optional inclusive `startDate` and `endDate` filters use `YYYY-MM-DD`. |
| `GET /transfers/{transferId}` | Return a transfer only if it belongs to the authenticated customer's outgoing history; otherwise return not found. |
| `GET /accounts` | Return the authenticated customer's accounts and balances. |
| `GET /accounts/transfer-limits` | Return configured per-transfer maximum and remaining daily/monthly amounts. |
| `GET /statistics/monthly-transfers` | Return one row for each of the current month and preceding five months, newest first. Each row has month, completed transfer count, and completed outgoing amount. Empty months have zero values. |

Date filters are inclusive calendar dates in `Europe/Istanbul`; implement them as a half-open timestamp range from the start of `startDate` through the start of the day after `endDate`. Reject malformed dates and `startDate > endDate`. Cap page size at 100. The statistics endpoint must return all six months, even when some months have no rows in the transfers table.

Example success envelope:

```json
{
  "data": {
    "id": "transfer-uuid",
    "status": "COMPLETED",
    "amount": "125.00",
    "currency": "TRY",
    "createdAt": "2026-11-18T18:11:24Z"
  },
  "meta": {}
}
```

Serialize monetary values as decimal strings or another documented decimal-safe representation; never serialize them through binary floating-point.

All errors use one shape and include a stable machine-readable code, a safe human-readable message, and a trace ID:

```json
{
  "error": {
    "code": "INSUFFICIENT_FUNDS",
    "message": "The source account has insufficient funds.",
    "traceId": "request-trace-id"
  }
}
```

Use appropriate HTTP statuses consistently: `400` for malformed/invalid input, `401` for unauthenticated requests, `403` for forbidden operations, `404` for inaccessible or missing resources, `409` for idempotency-key conflicts, and `422` for valid requests rejected by business rules (such as insufficient funds or exceeded limits). Do not expose SQL details, stack traces, account ownership, or other sensitive internals in error messages. Generate or propagate a trace ID for every request and include it in logs and error responses.

## 8. Security and privacy

- Use Spring Security as an OAuth2 resource server. Production authentication is assumed to use an OIDC provider and JWT access tokens; registration and identity-provider setup are outside this feature's scope.
- Map the verified token subject to an existing customer. Scope account, transfer, and statistics queries server-side to that customer.
- Authorize every resource lookup, not just list endpoints. Use not-found responses when revealing resource existence would expose another customer's data.
- Use TLS in deployed environments, validate input, configure restrictive CORS, and avoid logging access tokens, full IBANs, or sensitive request bodies.
- Keep secrets out of source control; use environment/secret-management configuration. Apply secure headers and production-safe error handling.
- For a browser client, use OIDC Authorization Code with PKCE. Keep access tokens out of persistent browser storage; define CSRF protection if authentication is ever moved to cookies.
- Provide only synthetic test accounts and credentials for local development. Do not use real customer or account data in fixtures.

## 9. Frontend

The React/TypeScript client includes:

- Transfer form for source account, internal receiver IBAN, amount, and description.
- Clear success state and distinct handling for insufficient funds, limit exceeded, validation, authentication, and network errors.
- Paged outgoing-transfer history with date filters and a detail view.
- Monthly statistics table for the six-month response.
- Account balances and remaining transfer limits.

Disable repeated submission while a request is in flight, but rely on the idempotency key for correctness across retries, refreshes, and network timeouts. Do not treat a client-side success state as authoritative until the server confirms the committed transfer.

## 10. Database changes and configuration

Use Flyway SQL migrations under `backend/src/main/resources/db/migration`, with forward-only, reviewed schema changes. Production startup must fail clearly on migration errors. Hibernate schema mode should be `validate` (or `none` where appropriate), never `create`/`update`.

Use PostgreSQL `timestamptz` for instants and compute business-period boundaries in the configured business timezone. Seed synthetic customers/accounts only in a local or test profile, never as production credentials. Store all monetary values at two-decimal precision and validate currency and amount constraints in both application logic and the database where possible.

Docker Compose configuration is for local development, not production credentials or data. The PostgreSQL service must have a health check, a named data volume, and configurable port/database/user/password values supplied through environment variables. Do not publish the database port beyond the local development machine by default. Application services must wait for database readiness and still report connection/migration failures explicitly. Production secrets and database provisioning are supplied by the deployment environment, not baked into images.

## 11. Testing and quality gates

- **Unit tests:** JUnit 5 and Mockito; run quickly without Spring application startup, a database, Docker, or network access. Cover amount/date validation, idempotency decisions, limit calculations, authorization decisions, and transfer use-case behavior. Mock external boundaries, not domain rules.
- **Backend integration tests:** JUnit 5, Spring Boot test support, and Testcontainers PostgreSQL. Start a disposable PostgreSQL instance matching the supported PostgreSQL major version; apply the real Flyway migrations and use the real database driver. Do not substitute H2 for PostgreSQL, since row locking, constraints, and SQL behavior are part of the requirements. Cover atomic debit/credit, rollback, duplicate/retried requests, concurrent limit enforcement, account locking, migrations, date boundaries, zero-filled monthly statistics, and customer data isolation.
- **API tests:** verify status codes, success/error envelopes, trace IDs, authentication, validation, and prevention of cross-customer reads. Use test security principals/tokens; do not require a live identity provider.
- **Frontend tests:** use Vitest and React Testing Library for component/unit tests and client integration behavior, including form validation, API success, business errors, and network failures. Do not require a live backend for routine frontend tests.
- **Test separation:** use Maven Surefire for fast `*Test` unit/API tests and Maven Failsafe for `*IT` database-backed integration tests. `mvn test` runs the fast test set; `mvn verify` runs the full backend suite, including Testcontainers integration tests. Document the Docker Engine/Desktop prerequisite for integration tests.
- **Coverage gate:** the backend test suite must achieve at least 80% functionality coverage, reported through JaCoCo. Apply the agreed threshold to application/domain functionality, excluding generated code and trivial accessors; report the measurement and exclusions in the README. Do not inflate coverage with tests that only assert constants or framework behavior.
- Maintain a Postman collection covering the successful flows and representative validation, limit, insufficient-funds, authorization, and idempotency cases.

## 12. Assumptions and decisions to confirm

> **Assumptions for the initial implementation**
>
> 1. The repository will become a single-repository monolith with `backend/` and `frontend/` directories.
> 2. Transfers are TRY-only, between active accounts already present in this system. The receiver IBAN is an internal account identifier, not an external bank destination.
> 3. History and monthly statistics represent completed outgoing transfers initiated by the logged-in customer.
> 4. The business timezone is `Europe/Istanbul`; date filters are inclusive local calendar dates.
> 5. The three limits are globally configurable defaults applied equally to customers. Per-customer tiers and an admin-managed limit policy are not in the initial scope.
> 6. A production OIDC identity provider will supply verified JWTs, but its vendor, customer provisioning process, and production credentials are not yet selected.
> 7. Only completed transfers are persisted in the initial model; failed attempts are represented by error responses and logs, not transfer records.
> 8. The case study gives no precise latency target. "Within seconds" is the functional expectation; a numeric SLO should be set after load testing and deployment assumptions are known.

Confirm these assumptions before adding external integrations, customer-specific policy, a different history definition, or production identity-provider wiring.
