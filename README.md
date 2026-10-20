# VaultCore

A complete practice banking API for Java/Spring Boot, PostgreSQL transactions,
security, concurrency, and Kubernetes operations. Uses simulated money and one
currency, UGX, with a fixed two-decimal practice model. Java 21 / Spring Boot 4.1.1.

## Start on a 4 GB machine

```bash
./scripts/setup.sh
python3 scripts/dev.py --build
```

Setup installs a checksum-verified local Java 21, Maven Wrapper, kubectl, and kind;
it does not start services. Docker/Compose and native PostgreSQL server tools
were already installed on the development machine. `dev.py` uses the native
PostgreSQL tools: it builds before starting services, starts one private database
on port 55432 and the API on 127.0.0.1:8080, and stops both on Ctrl+C. Data persists
under `~/.local/share/vaultcore/postgres`. It does not touch the system database.
`VAULTCORE_POSTGRES_BIN` can select a PostgreSQL bin directory on other systems.

The `dev` profile deliberately lets you choose identities and roles through
`POST /dev/token`. It binds to loopback and is for local practice only. Normal
mode has no token-issuance endpoint and validates tokens from an external issuer.
Never expose the dev profile on a shared/public server. CI enables it only inside
its disposable private cluster, using a shared signing-key Secret for replicas.

In another terminal:

```bash
python3 scripts/demo.py --state /tmp/vaultcore-demo.json
```

This creates demo accounts and verifies deposits, transfers, idempotent replay,
concurrent withdrawals, access control, and ledger reconciliation. Restart the
app with `dev.py`, then verify that state survived:

```bash
python3 scripts/demo.py --verify-state /tmp/vaultcore-demo.json
```

Tokens expire after 15 minutes. The demo mints fresh tokens and saves no tokens
in its state file. A process restart changes local signing keys by default.
Demo runs create persistent simulated transactions; they do not clear data.

## Verify

Choose a verification mode; do not run builds concurrently:

```bash
./scripts/mvn.sh verify                 # fast unit/API security tests, no services
./scripts/test-postgres.sh              # full suite with one temporary native PostgreSQL
# OR, on a Docker-capable machine:
MAVEN_OPTS='-XX:MaxMetaspaceSize=384m' ./scripts/mvn.sh -Pintegration verify
# same integration suite with Testcontainers/PostgreSQL 17
```

The Docker variant needs additional class-metadata space for Testcontainers;
use it on a machine with room for both the JVM and Docker. CI uses a 512 MB heap
and 384 MB metadata budget, while local defaults remain capped as described below.
The native test runner uses a fresh port and database and removes it on exit.
It never uses the system database or local practice data. Local verification
used PostgreSQL 16; containers and CI use PostgreSQL 17. CI also builds the image
and exercises two Kubernetes replicas, restart persistence, a database outage,
and recovery from a deliberately broken image rollout.

Maven/tests share one JVM with a 256 MB heap and no parallel test execution.
The API uses a 192 MB heap and three database connections. VS Code Java auto-build
is disabled. These caps do not cap total machine memory. No local cluster is
started automatically. `target/` is generated output; edit files under `src/`.

## API

All `/api/v1` routes require a bearer token. Account creation needs no body and
always assigns ownership from the token; it creates a zero-balance UGX account.

| Method / path | Behaviour / permission |
| --- | --- |
| POST `/api/v1/accounts` | Create your own account; returns 201 + Location |
| GET `/api/v1/accounts` | Your accounts; tellers/auditors/admins can list all customer accounts |
| GET `/api/v1/accounts/{id}` | Owner or teller/auditor/admin |
| POST `/api/v1/accounts/{id}/deposits` | Simulated cash-in; TELLER or ADMIN |
| POST `/api/v1/accounts/{id}/withdrawals` | Simulated cash-out; TELLER or ADMIN |
| POST `/api/v1/transfers` | Owner of source account, or ADMIN |
| GET `/api/v1/accounts/{id}/transactions` | Authorised account history, newest first |
| GET `/api/v1/transactions/{id}` | Owner of either customer account, or teller/auditor/admin |
| GET `/api/v1/ledger/reconciliation` | AUDITOR or ADMIN; balances versus postings |
| GET `/api/v1/status` | Authenticated service status |
| GET `/actuator/health/liveness` | Public; independent of database availability |
| GET `/actuator/health/readiness` | Public; includes database readiness |

Pagination: `?page=0&size=20`, with size 1..100. Responses use `items`, `page`,
`size`, and `total`. Monetary values use NUMERIC(19,2)/BigDecimal; no rounding.
Cash body: `{"amount":"100.00","currency":"UGX"}`. Transfer body adds
`sourceAccountId` and `destinationAccountId`. Decimal strings avoid client-side
floating-point conversion. Zero/negative amounts, unsupported currencies,
excess precision, self-transfers, frozen accounts, and overdrafts are rejected.

Every money POST requires `Idempotency-Key` (1..128 letters/digits or `._:-`).
Keys are scoped to authenticated subject and operation. A matching successful
retry returns the original 201 result with `Idempotency-Replayed: true`, even
after restart. Changing the payload returns 409 `IDEMPOTENCY_CONFLICT`. Failed
attempts roll back their key claims and may be retried. Successful records are
retained indefinitely. Account creation is not an idempotent money operation.

Business/validation errors use Problem Details with `code` and `requestId`.
Authentication errors use Spring Security's 401 bearer response. Each response
includes `X-Request-ID`; logs exclude request payloads and credentials. Common
statuses: 400 invalid input, 401 missing/invalid token, 403 forbidden, 404 missing
resource, 409 business conflict, 503 bounded retries exhausted.

Roles come from the JWT `roles` list or Keycloak `realm_access.roles`; recognised
names are CUSTOMER, TELLER, AUDITOR, ADMIN. Normal mode validates signature,
issuer, expiry, and configured audience. Set `JWT_ISSUER_URI` / `JWT_AUDIENCE` in
`.env` or environment for your external provider.

## Docker and Kubernetes

As an alternative to native development, start `docker compose up postgres` in
one terminal, then `./scripts/run.sh --spring.profiles.active=dev` in another.
`.env` is ignored and generated with a random database password. PostgreSQL binds
to 127.0.0.1:55432. Stop foreground commands with Ctrl+C and any leftover Compose
database with `docker compose stop`; its volume persists. Do not run native and
Compose databases on the same port. Keep `.env` credentials for existing data.

For Kubernetes use a machine with sufficient RAM. Build the JAR first, configure
a pod-reachable JWT issuer in `deploy/k8s/app.yaml`, then:

```bash
docker build -t vaultcore:dev .
kind create cluster --name vaultcore --config deploy/kind.yaml
kind load docker-image vaultcore:dev --name vaultcore
kubectl create secret generic vaultcore-secrets --from-literal=DB_PASSWORD='choose-a-local-password'
kubectl apply -f deploy/k8s/postgres.yaml -f deploy/k8s/app.yaml
kubectl rollout status deployment/vaultcore
```

The default deployment uses one replica and allows downtime to save memory.
The CI exercise applies its own two-replica rollout settings. PostgreSQL uses a
PVC; this exercise does not supply database high availability. Run
`./scripts/k8s-smoke.sh` only in a **fresh, disposable** `kind-vaultcore` cluster
instead of the manual apply steps; it creates its own Secrets and enables test
authentication. It leaves the cluster for inspection. Delete it when finished:
`kind delete cluster --name vaultcore`. CI always deletes its cluster.

The JRE and PostgreSQL images are digest-pinned; update pins deliberately. CI
builds but does not publish an image. Operational commands and design details are
in [architecture](docs/architecture.md); the completed milestones and optional
extensions are in [the plan](docs/plan.md).
