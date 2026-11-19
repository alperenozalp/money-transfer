# Money Transfer

A runnable money-transfer demo with a React UI, a Spring Boot API, and PostgreSQL. The local stack includes synthetic users, so you can start the app and try authenticated transfers without setting up an identity provider.

## Start the project

You need Docker Engine/Desktop with Docker Compose v2. From the repository root, run in PowerShell:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

Compose starts PostgreSQL, runs the backend with the `local` profile, applies Flyway migrations, seeds the demo customers/accounts, and starts the frontend.

| Service | Address |
| --- | --- |
| Frontend | <http://localhost:5173> |
| Backend API | <http://localhost:8080/api/v1> |
| Backend health | <http://localhost:8080/actuator/health> |
| PostgreSQL | `localhost:5432` |

Services are bound to loopback. PostgreSQL data is stored in the `postgres_data` Docker volume and survives restarts.

Stop the services without deleting data:

```powershell
docker compose down
```

Reset the local database, including transfer history and changed balances:

```powershell
docker compose down --volumes
```

The reset is destructive. `.env.example` contains local-only values; do not commit your `.env` file or use these settings with real data.

## Demo login

Use the customer ID and password in the UI or in the Postman environment. Each demo customer has one synthetic TRY account.

| Customer ID | Password | IBAN |
| --- | --- | --- |
| `demo-customer` | `demo1234` | `TR0000000000000000000000001` |
| `demo-recipient` | `transfer1234` | `TR0000000000000000000000003` |
| `demo-customer-3` | `money1234` | `TR0000000000000000000000004` |

These are public demo credentials, not private or production credentials. Successful transfers update both account balances and create ledger entries.

## Design and technical stack

| Area | Technology |
| --- | --- |
| Frontend | React 19.3.1, TypeScript 7.0.2, Vite 8.3.8, Node.js 24 |
| Backend | Java 21, Spring Boot 3.5.16, Maven 3.9.9 |
| Database | PostgreSQL 16, Spring Data JPA, Flyway |
| Tests | JUnit 5, Testcontainers, Vitest 5.1.1, React Testing Library 16.3.3 |
| Local run | Docker Compose v2 |

The frontend calls a customer-scoped REST API. Flyway owns schema changes; Hibernate validates the schema at startup. The API provides account and limit reads, transfer creation and history (including incoming transfers), and monthly transfer statistics.

Transfer integrity is enforced at the database and service layers:

- Transfers run inside a transaction and acquire pessimistic locks on the customer and account rows.
- A unique sender-scoped idempotency key prevents duplicate execution on retries.
- Amounts use `BigDecimal` in the API and `NUMERIC(19,2)` in PostgreSQL.
- The transfer, balance changes, and matching debit/credit ledger entries commit together.

Default limits are 50,000 TRY per transfer, 100,000 TRY daily, and 500,000 TRY monthly. They can be changed with `TRANSFER_LIMITS_PER_TRANSFER`, `TRANSFER_LIMITS_DAILY`, and `TRANSFER_LIMITS_MONTHLY`. The default business timezone is `Europe/Istanbul`; set `BUSINESS_TIME_ZONE` to change it.

## API response format

Successful endpoints use a shared `ApiResponse` envelope. `data` contains the endpoint result, and `meta` contains paging details when relevant:

```json
{
  "data": {},
  "meta": {}
}
```

Errors use a shared `ApiErrorResponse` envelope with a stable code, a safe human-readable message, and a trace ID:

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "The request is invalid.",
    "traceId": "request-trace-id"
  }
}
```

The `X-Trace-Id` response header carries the same ID as `error.traceId`. The backend generates a trace ID when the request does not provide a valid one.

## Local authentication, protections, and assumptions

The case-study demo does not depend on an external identity provider. The local profile therefore provides a small customer-ID/password login that issues a short-lived JWT. This lets a new user exercise the same authenticated, customer-scoped API without first registering an identity-provider account.

The local flow is deliberately limited to evaluation:

- Demo passwords are checked with BCrypt; JWTs are signed with an RSA key generated at backend startup and validated for signature, issuer, and expiry.
- Tokens expire after 30 minutes. Since the signing key is regenerated at every startup, tokens from an earlier run stop working after a restart.
- The demo login and synthetic seed data are enabled only by the `local` profile and are not enabled with `prod`.
- Compose publishes the application ports on loopback. Use only synthetic data and never deploy the local demo credentials or mode.
- A non-local deployment must configure a trusted issuer with `OIDC_ISSUER_URI` and provide customer provisioning. Production identity-provider registration and credentials are outside this demo.

**Assumptions:** The three seeded customers and their accounts are test fixtures; balances and transfer history persist in the local Docker volume until it is reset. A React UI is included for convenience, but the backend API can also be exercised with Postman.

## Tests

### Backend

Requires JDK 21 and Maven 3.9.9 or newer. From the repository root:

```powershell
Set-Location backend
mvn test
mvn verify
```

`mvn test` runs the fast unit-test suite. `mvn verify` also runs the PostgreSQL integration tests through Testcontainers, so Docker must be running; it also checks the configured 80% instruction-coverage threshold.

### Frontend

Requires Node.js 24 and npm. From the repository root:

```powershell
Set-Location frontend
npm ci
npm test
npm run build
```

`npm run build` type-checks the TypeScript and creates the production frontend bundle. `npm run test:coverage` generates a local frontend coverage report.

## Postman

Import `docs/postman/money-transfer.postman_collection.json` and `docs/postman/money-transfer.local.postman_environment.json.example`, then select the **Money Transfer local demo** environment. Send **Create demo session** first; the collection saves the access token. Send **List accounts** next; it saves the source account ID. The example environment uses `demo-customer` as sender and `demo-recipient` as receiver.

**Create transfer** sends real synthetic funds. **Replay transfer** resends the same request with the same idempotency key; **Reuse idempotency key with a different body** demonstrates the conflict response. To inspect incoming transfers, change the environment credentials to `demo-recipient` / `transfer1234`, create a new session, and send **List incoming transfers**.
