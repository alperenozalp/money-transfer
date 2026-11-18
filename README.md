# Money Transfer

Initial monorepo scaffold for the internal money-transfer case study. It contains the backend and frontend build/test foundations, local Docker Compose services, and a Postman collection template. Business capabilities and REST endpoints have not been implemented yet.

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

The React development server is available at <http://localhost:5173>, the backend at <http://localhost:8080>, and PostgreSQL is published only on `127.0.0.1` using the port in `.env`. PostgreSQL data is kept in the `postgres_data` named volume. The backend and frontend are development services; no transfer endpoints or OIDC provider are configured yet.

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

`mvn test` runs the fast JUnit 5/Surefire suite. `mvn verify` also runs `*IT` integration tests through Failsafe. The initial PostgreSQL Testcontainers smoke test skips when Docker is unavailable; database-backed integration tests require a running Docker Engine. No H2 substitute is configured. The Flyway migration directory is `backend/src/main/resources/db/migration`.

JaCoCo reports are generated under `backend/target/site/jacoco/`. The 80% instruction-coverage gate is intended for application/domain functionality; the Spring Boot entry point and generated code are excluded. Trivial accessors should not be used to inflate the measured coverage. The scaffold has no implemented application/domain functionality yet, so meaningful coverage will be established as those capabilities and their tests are added.

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

Import `docs/postman/money-transfer.postman_collection.json` and optionally `docs/postman/money-transfer.local.postman_environment.json.example`. These are request templates for the specified API, not runnable examples until the API and local authentication setup exist. Use synthetic data only.
