# Money Transfer

Internal money-transfer case study. The backend currently implements authenticated account listing and transfer-limit reads; transfer execution and the other specified endpoints are not implemented yet.

## Selected versions

| Component | Version |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.5.16 |
| Maven container | 3.9.9 |
| PostgreSQL | 16 |
| Node.js container | 24 |
| React / React DOM | 19.3.1 |
| Vite | 8.3.8 |
| TypeScript | 7.0.2 |
| Vitest | 5.1.1 |
| React Testing Library | 16.3.3 |
| Surefire / Failsafe | 3.6.1 |
| JaCoCo | 0.8.13 |

Spring Boot manages the versions of its Java dependencies, including JUnit 5 and Testcontainers. Frontend package versions are recorded in `frontend/package-lock.json`.

## Local development with Docker

Prerequisites: Docker Engine/Desktop with Docker Compose v2.

In PowerShell, from the repository root:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

The React development server is available at <http://localhost:5173>, the backend at <http://localhost:8080>, and PostgreSQL is published only on `127.0.0.1` using the port in `.env`. PostgreSQL data is kept in the `postgres_data` named volume. The local Compose stack includes three synthetic demo customers and their accounts, so no external identity provider or manual customer provisioning is needed to try the case study.

Stop the services and keep local database data:

```powershell
docker compose down
```

Reset local database data (this permanently removes the Compose database volume):

```powershell
docker compose down --volumes
```

Do not commit `.env`; `.env.example` contains local-only placeholders and can be copied to start development.

## Backend

Requires JDK 21 and Maven 3.9.9 or newer for local commands. The backend image uses a multi-stage build and runs the packaged application as a non-root user.

```powershell
Set-Location backend
mvn test
mvn verify
```

`mvn test` runs the fast JUnit 5/Surefire suite. `mvn verify` also runs `*IT` integration tests through Failsafe. The account and transfer API integration tests use Testcontainers PostgreSQL and require a running Docker Engine. No H2 substitute is configured. Flyway applies the versioned customer, account, transfer, and ledger schema from `backend/src/main/resources/db/migration`; the `local` profile then seeds synthetic demo customers and accounts.

The UI shows account balances, remaining limits, monthly transfer totals, and outgoing transfer history; transfer creation requires an active TRY account. The account endpoints are `GET /api/v1/accounts` and `GET /api/v1/accounts/transfer-limits`. Transfers are created with `POST /api/v1/transfers` (requires an `Idempotency-Key` header), listed with `GET /api/v1/transfers`, and viewed with `GET /api/v1/transfers/{transferId}`. Monthly totals are available from `GET /api/v1/statistics/monthly-transfers`. Listing accepts optional inclusive `startDate`/`endDate` values in `YYYY-MM-DD` form and `page`/`size` pagination parameters (maximum size 100). A successful transfer debits and credits the two account balances and writes matching ledger entries in one database transaction. Defaults are 50,000 TRY per transfer, 100,000 TRY daily, and 500,000 TRY monthly. Override them with `TRANSFER_LIMITS_PER_TRANSFER`, `TRANSFER_LIMITS_DAILY`, and `TRANSFER_LIMITS_MONTHLY`; override the business timezone with `BUSINESS_TIME_ZONE` (default `Europe/Istanbul`).

The local Compose profile provides a customer ID/password form. These local-only demo credentials and synthetic account IBANs are:

| Customer ID | Password | Account IBAN(s) |
| --- | --- | --- |
| `demo-customer` | `demo1234` | `TR0000000000000000000000001` |
| `demo-recipient` | `transfer1234` | `TR0000000000000000000000003` |
| `demo-customer-3` | `money1234` | `TR0000000000000000000000004` |

The login IDs map to the seeded customer identity subjects. The demo endpoint checks BCrypt password hashes and issues a short-lived JWT for the matching customer; the token is then used for the same authenticated account, transfer, and statistics APIs. The backend generates a fresh RSA signing key on startup and validates the demo token's signature, issuer, and expiry; the key is not a shared or committed secret. Synthetic seed data is inserted only when missing, so restarting Compose preserves demo transfers and balances. To reset that data, use the documented `docker compose down --volumes` command.

This is an explicit case-study assumption: the supplied PDF makes the React UI optional, treats security as a priority, and does not require or select an identity provider. A local demo JWT keeps the repository easy to run without an extra identity-provider service while still exercising authenticated, customer-scoped APIs. The demo endpoint and seed data exist only with Spring's `local` profile; Compose publishes the services on loopback and uses synthetic data. This mode is for local evaluation only and must not be used with real customer data or deployed.

For a non-local deployment, configure a trusted issuer with `OIDC_ISSUER_URI` and run with the `prod` profile. The demo session endpoint and local seed are not registered outside `local`; startup with the `prod` profile fails if no issuer is configured. Production identity-provider registration, credentials, and customer provisioning remain deployment responsibilities.

The Postman collection is at `docs/postman/money-transfer.postman_collection.json`. To try it locally, create a demo session with `POST http://localhost:8080/api/v1/demo/session` and a JSON body such as `{"customerId":"demo-customer","password":"demo1234"}`, then set the collection's `accessToken`, `sourceAccountId`, and `receiverIban` variables to the returned token and synthetic account values.

JaCoCo reports are generated under `backend/target/site/jacoco/`. The 80% instruction-coverage gate is intended for application/domain functionality; the Spring Boot entry point and generated code are excluded. Trivial accessors should not be used to inflate the measured coverage.

## Frontend

Requires Node.js 24 and npm.

```powershell
Set-Location frontend
npm ci
npm test
npm run build
```

Vitest uses jsdom and React Testing Library. `npm run test:coverage` generates a local coverage report. Vite proxies `/api` requests to `http://localhost:8080` for local development; Docker Compose overrides the target to the backend service.

## Postman

Import `docs/postman/money-transfer.postman_collection.json` and optionally `docs/postman/money-transfer.local.postman_environment.json.example`. API requests require a valid access token and a provisioned customer. Use synthetic data only.
