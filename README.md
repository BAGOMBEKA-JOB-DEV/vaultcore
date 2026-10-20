# VaultCore

A banking transaction API with account management, deposits, withdrawals,
transfers, an immutable balanced ledger, and durable idempotency. Supports UGX
with exact two-decimal amounts. Built with Java 21, Spring Boot 4.1.1, PostgreSQL,
and Kubernetes.

Start with [Docker Compose](#quick-start-with-docker-compose), [other runtime options](#other-runtime-options),
[verification](#verify), and the [API contract](#api). The [command reference](#command-reference)
covers manual API calls, Docker, databases, Kubernetes, recovery, cleanup, and Git.
Run commands from the repository root and choose one runtime at a time.

## Quick start with Docker Compose

Requirements: Git, Docker Engine, and Docker Compose v2. The image builds the
application from source; a local Java or Maven installation is optional.

On first setup, copy the environment template and set `DB_PASSWORD` in `.env` to
a unique password. `openssl rand -hex 24` can generate one. Keep an existing
`.env` and its password when reusing a database volume.

```bash
cp .env.example .env
# Edit .env and replace DB_PASSWORD before starting.
docker compose up --build --detach --wait --wait-timeout 180
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

Compose builds the API, waits for PostgreSQL to become healthy, then starts the
API at `http://127.0.0.1:8080`. Flyway applies migrations automatically. PostgreSQL
is available at `127.0.0.1:55432`, with persistent data in a named volume. Set
`API_PORT` or `DB_PORT` in `.env` to change host ports.

```bash
docker compose ps
docker compose logs --follow app       # Ctrl+C ends the log stream
docker compose restart app
docker compose down                    # retains database data
```

The Compose default uses the `dev` profile, whose `POST /dev/token` endpoint
allows choosing identities and roles. It is for local development only; keep it
off shared/public servers. Published ports bind to loopback. For external JWT
authentication, set `SPRING_PROFILES_ACTIVE=default`, `JWT_ISSUER_URI`, and
`JWT_AUDIENCE` in `.env`, then recreate the app with `docker compose up -d app`.
The issuer must be reachable from inside the API container. Normal mode has no
token-issuance endpoint and validates the external issuer's tokens.

Optional API smoke checks use Python 3:

```bash
python3 scripts/demo.py --state /tmp/vaultcore-state.json
docker compose restart app
docker compose up --detach --wait --wait-timeout 180 app
python3 scripts/demo.py --verify-state /tmp/vaultcore-state.json
```

These checks create accounts and transactions, then verify deposits, transfers,
idempotent replay, concurrent withdrawals, permissions, and reconciliation.
Tokens expire after 15 minutes; the client issues fresh tokens and never saves
them in its state file. Application restarts replace development signing keys
unless a shared key file is configured. Data is retained between runs.

## Other runtime options

**Docker without Compose:** use the complete [docker build/run commands](#docker-without-compose)
below to create the network, persistent database, and API containers directly.

**Java with a Docker database:** install JDK 21, prepare `.env` as above, and run:

```bash
docker compose up --detach --wait postgres
./mvnw --batch-mode verify
java -jar target/vaultcore-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

Stop the API with Ctrl+C, then stop the database with `docker compose stop postgres`.
The application reads database settings from `.env`. To use an existing database,
set `DB_URL`, `DB_USER`, and `DB_PASSWORD` and omit the Compose command. For
external JWT authentication, configure the issuer/audience and pass
`--spring.profiles.active=default` instead of `dev`.

**Optional helper scripts:** the Linux installer and native development runner
remain available:

```bash
./scripts/setup.sh
python3 scripts/dev.py --build
```

Setup installs checksum-verified Java 21, Maven Wrapper, kubectl, and kind without
starting services. The native runner requires PostgreSQL server tools, starts
its own database and the API, and stops both on Ctrl+C. Its data persists under
`~/.local/share/vaultcore/postgres`. `VAULTCORE_POSTGRES_BIN` selects another
PostgreSQL bin directory. Stop other runtimes before using the same host ports.

## Verify

With JDK 21 installed:

```bash
./mvnw --batch-mode verify             # unit and API security tests; no services
MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=384m' ./mvnw --batch-mode -Pintegration verify
# Integration tests start PostgreSQL 17 through Testcontainers; Docker is required.
```

Alternatively, `./scripts/mvn.sh` selects the helper-installed JDK, and
`./scripts/test-postgres.sh` runs the full suite using a temporary native
PostgreSQL instance. The native runner removes its database on exit and keeps
application data separate. Run verification commands sequentially. Reports are
written to `target/surefire-reports` and `target/failsafe-reports`.

CI runs the PostgreSQL integration suite, builds the image from source, verifies
Compose startup and API operations, then checks two Kubernetes replicas, restart
persistence, a database outage, and recovery from a broken image rollout.
`target/` contains generated output; edit source files under `src/`.

## API

All `/api/v1` routes require a bearer token. Account creation needs no body and
always assigns ownership from the token; it creates a zero-balance UGX account.

| Method / path | Behaviour / permission |
| --- | --- |
| POST `/api/v1/accounts` | Create your own account; returns 201 + Location |
| GET `/api/v1/accounts` | Your accounts; tellers/auditors/admins can list all customer accounts |
| GET `/api/v1/accounts/{id}` | Owner or teller/auditor/admin |
| POST `/api/v1/accounts/{id}/deposits` | Deposit; TELLER or ADMIN |
| POST `/api/v1/accounts/{id}/withdrawals` | Withdrawal; TELLER or ADMIN |
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

## Kubernetes

Build the image directly from source, configure a pod-reachable external JWT
issuer in `deploy/k8s/app.yaml`, and deploy to a local kind cluster:

```bash
docker build -t vaultcore:dev .
kind create cluster --name vaultcore --config deploy/kind.yaml --wait 120s
kind load docker-image vaultcore:dev --name vaultcore
vaultcore_db_password="$(openssl rand -hex 24)"
kubectl create secret generic vaultcore-secrets --from-literal=DB_PASSWORD="$vaultcore_db_password"
unset vaultcore_db_password
kubectl apply -f deploy/k8s/postgres.yaml -f deploy/k8s/app.yaml
kubectl rollout status statefulset/postgres --timeout=180s
kubectl rollout status deployment/vaultcore --timeout=240s
```

The default deployment uses one API replica. The CI configuration uses two
replicas and keeps an available replica during rolling updates. PostgreSQL uses
a PVC; database high availability is outside the current deployment.

The [command reference](#kubernetes-cluster-and-deployment) includes manual
configuration, scaling, logs, rollback, and cleanup. The optional
`./scripts/k8s-smoke.sh` runs automated recovery checks in a **fresh, disposable**
`kind-vaultcore` cluster and configures private test authentication. Delete the
cluster with `kind delete cluster --name vaultcore` when finished.

The JDK, JRE, and PostgreSQL images are digest-pinned. CI builds images without
publishing them. See [architecture](docs/architecture.md) for design details and
[the plan](docs/plan.md) for delivered milestones and future work.

## Command reference

This is the project operating reference. It covers the supported workflows and
the Kubernetes operations used by CI, with commands for each supported runtime.
Installer download/checksum details remain in [setup.sh](scripts/setup.sh); exact CI commands
are in [ci.yaml](.github/workflows/ci.yaml) and [k8s-smoke.sh](scripts/k8s-smoke.sh).

### Tools, build, and local runtime

Docker workflows require Docker Engine and Compose v2. Building on the host also
requires JDK 21. Python 3 is needed only for the optional smoke client and native
runner. Check installed tools with:

```bash
docker --version
docker compose version
java -version
./mvnw --version
psql --version
kubectl version --client
kind version
```

For Docker installation, follow the [official Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/).
The optional `./scripts/setup.sh` installs the project JDK and Kubernetes CLIs;
afterwards, `export PATH="$HOME/.local/bin:$PATH"` makes those tools available.
Native development/testing also requires PostgreSQL server tools. On Ubuntu:

```bash
sudo apt-get update
sudo apt-get install git python3 curl unzip openssl postgresql
```

The PostgreSQL package can start a system database; the native project runners
create their own instance. Choose the build/test command you need:

| Task | Standard command | Optional helper |
| --- | --- | --- |
| Build and unit/API tests | `./mvnw verify` | `./scripts/mvn.sh verify` |
| Package without running tests | `./mvnw -DskipTests package` | `./scripts/mvn.sh -DskipTests package` |
| Money unit tests | `./mvnw -Dtest=MoneyTest test` | `./scripts/mvn.sh -Dtest=MoneyTest test` |
| Clean and verify | `./mvnw clean verify` | `./scripts/mvn.sh clean verify` |
| API against an existing database | `java -jar target/vaultcore-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev` | `./scripts/run.sh --spring.profiles.active=dev` |

Full integration tests with a disposable PostgreSQL container:

```bash
MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=384m' ./mvnw -Pintegration verify
MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=384m' ./mvnw -Pintegration -Dit.test=DatabaseIT verify
```

With PostgreSQL server tools installed, the native alternative is:

```bash
./scripts/test-postgres.sh
./scripts/test-postgres.sh -Dit.test=DatabaseIT
```

`clean` removes generated `target/` output. For the optional native runner:

```bash
python3 scripts/dev.py --build          # verify/build, then start native DB + API
python3 scripts/dev.py                  # reuse the built JAR
python3 scripts/dev.py --help
python3 scripts/demo.py --help
SERVER_PORT=8081 VAULTCORE_DEV_DB_PORT=55433 python3 scripts/dev.py
VAULTCORE_DEV_DATA_DIR="$HOME/.local/share/vaultcore/instance-two" python3 scripts/dev.py
VAULTCORE_POSTGRES_BIN=/usr/lib/postgresql/16/bin ./scripts/test-postgres.sh
```

Stop each native runner with Ctrl+C before using the same database port. The
second data-directory example still uses the configured port. Native directory
overrides apply to the native runner; Compose and Kubernetes manage separate
storage.

### Manual API commands

These examples use the local dev profile. Keep the API running in a separate
terminal. For a forwarded private Kubernetes deployment, change the URL to
`http://127.0.0.1:18080`. Normal mode requires tokens from your external provider
instead of `/dev/token`.

Create unique development identities and keep tokens in shell variables:

```bash
vaultcore_url=http://127.0.0.1:8080
vaultcore_run="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
vaultcore_token() {
  curl -fsS "$vaultcore_url/dev/token" -H 'Content-Type: application/json' \
    --data "{\"subject\":\"$1\",\"roles\":[\"$2\"]}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])'
}
vaultcore_alice="$(vaultcore_token "$vaultcore_run-alice" CUSTOMER)"
vaultcore_bob="$(vaultcore_token "$vaultcore_run-bob" CUSTOMER)"
vaultcore_teller="$(vaultcore_token "$vaultcore_run-teller" TELLER)"
vaultcore_auditor="$(vaultcore_token "$vaultcore_run-auditor" AUDITOR)"

vaultcore_source="$(curl -fsS -X POST "$vaultcore_url/api/v1/accounts" \
  -H "Authorization: Bearer $vaultcore_alice" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
vaultcore_destination="$(curl -fsS -X POST "$vaultcore_url/api/v1/accounts" \
  -H "Authorization: Bearer $vaultcore_bob" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

curl -fsS "$vaultcore_url/actuator/health/liveness"
curl -fsS "$vaultcore_url/actuator/health/readiness"
curl -fsS "$vaultcore_url/api/v1/status" -H "Authorization: Bearer $vaultcore_alice"
curl -fsS "$vaultcore_url/api/v1/accounts?page=0&size=20" -H "Authorization: Bearer $vaultcore_alice"
curl -fsS "$vaultcore_url/api/v1/accounts/$vaultcore_source" -H "Authorization: Bearer $vaultcore_alice"
```

Deposit, transfer, replay, and withdraw:

```bash
curl -i "$vaultcore_url/api/v1/accounts/$vaultcore_source/deposits" \
  -H "Authorization: Bearer $vaultcore_teller" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $vaultcore_run-fund" --data '{"amount":"100.00","currency":"UGX"}'

vaultcore_transfer_body="{\"sourceAccountId\":\"$vaultcore_source\",\"destinationAccountId\":\"$vaultcore_destination\",\"amount\":\"25.00\",\"currency\":\"UGX\"}"
vaultcore_transaction="$(curl -fsS "$vaultcore_url/api/v1/transfers" \
  -H "Authorization: Bearer $vaultcore_alice" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $vaultcore_run-transfer" --data "$vaultcore_transfer_body" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

# Same body/key: 201 and Idempotency-Replayed: true; money moves once.
curl -i "$vaultcore_url/api/v1/transfers" \
  -H "Authorization: Bearer $vaultcore_alice" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $vaultcore_run-transfer" --data "$vaultcore_transfer_body"

curl -i "$vaultcore_url/api/v1/accounts/$vaultcore_source/withdrawals" \
  -H "Authorization: Bearer $vaultcore_teller" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $vaultcore_run-withdraw" --data '{"amount":"5.00","currency":"UGX"}'

curl -fsS "$vaultcore_url/api/v1/accounts/$vaultcore_source/transactions?page=0&size=20" \
  -H "Authorization: Bearer $vaultcore_alice"
curl -fsS "$vaultcore_url/api/v1/transactions/$vaultcore_transaction" -H "Authorization: Bearer $vaultcore_bob"
curl -fsS "$vaultcore_url/api/v1/ledger/reconciliation" -H "Authorization: Bearer $vaultcore_auditor"

# Expected 403: Bob cannot read Alice's account.
curl -i "$vaultcore_url/api/v1/accounts/$vaultcore_source" -H "Authorization: Bearer $vaultcore_bob"
# Expected 409: overdraft rejected; balances remain unchanged.
curl -i "$vaultcore_url/api/v1/accounts/$vaultcore_source/withdrawals" \
  -H "Authorization: Bearer $vaultcore_teller" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $vaultcore_run-overdraft" --data '{"amount":"999.00","currency":"UGX"}'
```

Use `python3 scripts/demo.py` for automated concurrent requests. For its restart
check use `--state /tmp/vaultcore-state.json`, restart the app, then use
`--verify-state /tmp/vaultcore-state.json`. Add `--base-url` when using another port.

### Docker Compose commands

Prepare `.env` as described in the quick start. To run both services, or only the
database for a host Java process:

```bash
docker compose config --quiet
docker compose build app
docker compose up --detach --wait --wait-timeout 180
# Alternative: database only; run the Java command from Other runtime options.
docker compose up --detach --wait postgres
```

The database-only alternative does not stop an app container that is already
running. Stop that app with `docker compose stop app` before running the API on
the host. Common operations:

```bash
docker compose ps
docker compose logs --tail=100 app postgres
docker compose logs --follow app       # Ctrl+C ends the log stream
docker compose exec postgres pg_isready -U vaultcore -d vaultcore
docker compose exec postgres psql -U vaultcore -d vaultcore
docker compose restart app
docker compose stop                    # retain containers and database volume
docker compose down                    # remove containers/network; retain database volume
```

`docker compose down --volumes` **deletes Compose database data**. Keep `.env`
credentials unchanged when reusing a volume; changing environment variables does
not change an existing PostgreSQL user's password.

### Docker without Compose

These commands use the default database name/user and host ports. Stop other
runtimes using ports 8080/55432 first. Prepare `.env` as in the quick start, then
read its unquoted `DB_PASSWORD` value for both containers:

```bash
vaultcore_db_password="$(sed -n 's/^DB_PASSWORD=//p' .env)"
test -n "$vaultcore_db_password"
docker build -t vaultcore:dev .
docker network create vaultcore-net
docker volume create vaultcore-postgres-data
docker run --detach --name vaultcore-db --network vaultcore-net \
  --publish 127.0.0.1:55432:5432 \
  --env POSTGRES_DB=vaultcore --env POSTGRES_USER=vaultcore \
  --env POSTGRES_PASSWORD="$vaultcore_db_password" \
  --mount source=vaultcore-postgres-data,target=/var/lib/postgresql/data \
  --health-cmd='pg_isready -U vaultcore -d vaultcore' \
  --health-interval=10s --health-timeout=3s --health-retries=5 \
  postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24
```

Wait for the database to report `healthy` before starting the API:

```bash
docker inspect --format='{{.State.Health.Status}}' vaultcore-db
docker exec vaultcore-db pg_isready -U vaultcore -d vaultcore
docker run --detach --name vaultcore-api --network vaultcore-net \
  --publish 127.0.0.1:8080:8080 --env-file .env \
  --env DB_URL=jdbc:postgresql://vaultcore-db:5432/vaultcore \
  --env DB_USER=vaultcore --env DB_PASSWORD="$vaultcore_db_password" \
  --env SERVER_ADDRESS=0.0.0.0 --env SERVER_PORT=8080 \
  --env SPRING_PROFILES_ACTIVE=dev vaultcore:dev
unset vaultcore_db_password
docker ps
docker logs --follow vaultcore-api      # Ctrl+C ends the log stream
```

The API has a readiness healthcheck. Check its status or query it directly once
startup completes:

```bash
docker inspect --format='{{.State.Health.Status}}' vaultcore-api
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
docker image inspect vaultcore:dev
docker restart vaultcore-api
docker exec -it vaultcore-db psql -U vaultcore -d vaultcore
```

For external authentication, use `--env SPRING_PROFILES_ACTIVE=default` in the
API command and configure a container-reachable `JWT_ISSUER_URI` in `.env`.
Stop and remove the containers and network when finished; the volume remains:

```bash
docker stop vaultcore-api vaultcore-db
docker rm vaultcore-api vaultcore-db
docker network rm vaultcore-net
```

To restart later, rerun the container-creation commands with the same `.env` and
volume, omitting `docker network create` if the network still exists.
`docker volume rm vaultcore-postgres-data` **deletes the database data** after its
container is removed. This volume is separate from Compose's volume.

### Database inspection

Use `docker compose exec postgres psql -U vaultcore -d vaultcore` or
`docker exec -it vaultcore-db psql -U vaultcore -d vaultcore` for containers.
While the optional native runner is active, use its private Unix socket:

```bash
psql --host="${XDG_DATA_HOME:-$HOME/.local/share}/vaultcore/postgres" \
  --port=55432 --username=vaultcore --dbname=vaultcore
```

Adjust the user/database/port if configured differently. Inside psql:

```sql
\dt
SELECT id, account_type, currency, balance, status FROM accounts;
SELECT operation, count(*) FROM bank_transactions GROUP BY operation;
SELECT transaction_id, sum(amount), count(*) FROM ledger_entries
GROUP BY transaction_id HAVING sum(amount) <> 0 OR count(*) <> 2;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
\q
```

The ledger query should return zero rows. Stopping runtimes preserves their data;
`mvn clean` only removes build output.

### Kubernetes cluster and deployment

Build the image as above, then create/select the local cluster:

```bash
kind get clusters
kind create cluster --name vaultcore --config deploy/kind.yaml --wait 120s
kubectl config get-contexts
kubectl config use-context kind-vaultcore
kubectl config current-context
kubectl cluster-info
kubectl get nodes -o wide
kind load docker-image vaultcore:dev --name vaultcore
```

**Optional automated checks:** in that fresh cluster, run:

```bash
./scripts/k8s-smoke.sh
```

It creates Secrets/deployments itself and runs the complete CI verification. Do not
pre-create the same Secrets with the manual path. It leaves the cluster
available for inspection. CI runs the same script and deletes its cluster after.

**Manual deployment path:** instead of the automated script, first configure the
external issuer in `deploy/k8s/app.yaml`, then deploy:

```bash
vaultcore_db_password="$(openssl rand -hex 24)"
kubectl create secret generic vaultcore-secrets --from-literal=DB_PASSWORD="$vaultcore_db_password"
unset vaultcore_db_password
kubectl apply -f deploy/k8s/postgres.yaml -f deploy/k8s/app.yaml
kubectl rollout status statefulset/postgres --timeout=180s
kubectl rollout status deployment/vaultcore --timeout=240s
kubectl get deployments,statefulsets,pods,services,pvc,configmaps,secrets
```

For a **private disposable development cluster** without an external issuer, the manual
equivalent of CI's shared-key/dev-profile setup is below. This enables deliberate
test token issuance and two replicas; keep it off shared/public deployments.

```bash
vaultcore_key_dir="$(mktemp -d /tmp/vaultcore-keys.XXXXXX)"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$vaultcore_key_dir/private-key.pem"
chmod 600 "$vaultcore_key_dir/private-key.pem"
kubectl create secret generic vaultcore-dev-key --from-file=private-key.pem="$vaultcore_key_dir/private-key.pem"
rm "$vaultcore_key_dir/private-key.pem"
rmdir "$vaultcore_key_dir"
unset vaultcore_key_dir
kubectl patch deployment vaultcore --type=strategic --patch-file deploy/k8s/ci-patch.yaml
kubectl rollout status deployment/vaultcore --timeout=240s
```

Forward the API in a foreground terminal; stop forwarding with Ctrl+C:

```bash
kubectl port-forward service/vaultcore 18080:8080 --address=127.0.0.1
```

In another terminal:

```bash
curl -fsS http://127.0.0.1:18080/actuator/health/liveness
curl -fsS http://127.0.0.1:18080/actuator/health/readiness
python3 scripts/demo.py --base-url http://127.0.0.1:18080 --state /tmp/vaultcore-k8s-state.json
```

The smoke client requires the private dev-profile setup. Normal-mode API calls require
your issuer's tokens. A pod restart can close a port-forward connection: stop it
and run the forwarding command again before verifying persisted state.

### Kubernetes scaling, rollout, and recovery drills

Run these only against the disposable `kind-vaultcore` context. Two-replica dev
authentication requires the shared key above. The normal external-issuer mode
uses its external verification keys. Scale and perform a rolling restart:

```bash
kubectl config current-context
kubectl scale deployment vaultcore --replicas=2
kubectl patch deployment vaultcore --type=merge \
  -p '{"spec":{"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":1,"maxUnavailable":0}}}}'
kubectl rollout status deployment/vaultcore --timeout=180s
kubectl rollout history deployment/vaultcore
kubectl rollout restart deployment/vaultcore
kubectl rollout status deployment/vaultcore --timeout=180s
```

Reconnect port-forwarding, then verify the saved state:

```bash
python3 scripts/demo.py --base-url http://127.0.0.1:18080 --verify-state /tmp/vaultcore-k8s-state.json
```

Database outage and recovery; the PVC retains data while PostgreSQL is stopped:

```bash
kubectl scale statefulset postgres --replicas=0
kubectl wait pod -l app=vaultcore --for=condition=Ready=false --timeout=90s
# Through an existing port-forward: liveness should be 200, readiness should be 503.
curl -i http://127.0.0.1:18080/actuator/health/liveness
curl -i http://127.0.0.1:18080/actuator/health/readiness
kubectl scale statefulset postgres --replicas=1
kubectl rollout status statefulset/postgres --timeout=180s
kubectl wait pod -l app=vaultcore --for=condition=Ready --timeout=180s
```

Deliberately broken image and rollback; the rollout-status timeout is expected:

```bash
kubectl set image deployment/vaultcore api=vaultcore:intentionally-missing
kubectl rollout status deployment/vaultcore --timeout=20s
kubectl get pods
kubectl get events --sort-by=.lastTimestamp
kubectl rollout undo deployment/vaultcore
kubectl rollout status deployment/vaultcore --timeout=180s
```

The automated smoke script also runs an in-cluster availability-check Job during
the restart. Its exact Job command, per-replica forwarding, and assertions are in
[k8s-smoke.sh](scripts/k8s-smoke.sh).

To run that availability monitor manually in the disposable cluster, start it
before the rolling restart and wait for completion afterwards:

```bash
kubectl delete job vaultcore-availability --ignore-not-found
kubectl create job vaultcore-availability --image=vaultcore:dev -- /bin/sh -c \
  'for attempt in $(seq 1 60); do wget -q -T 3 -O /dev/null http://vaultcore:8080/actuator/health/readiness || exit 1; sleep 1; done'
kubectl wait pod -l job-name=vaultcore-availability --for=condition=Ready --timeout=60s
kubectl rollout restart deployment/vaultcore
kubectl rollout status deployment/vaultcore --timeout=180s
kubectl wait job/vaultcore-availability --for=condition=Complete --timeout=120s
kubectl logs job/vaultcore-availability
kubectl delete job vaultcore-availability
```

To target one replica for manual replay checks, list pods and choose a ready pod
without a Terminating status, then forward that pod instead of the Service:

```bash
kubectl get pods -l app=vaultcore
vaultcore_pod=replace-with-a-ready-pod-name-from-the-list
kubectl port-forward "pod/$vaultcore_pod" 18080:8080 --address=127.0.0.1
```

Stop that forward with Ctrl+C, choose the other ready replica, and forward it on
the same port before running `demo.py --verify-state` against the saved state.

### Kubernetes diagnostics and cleanup

```bash
kubectl get pods -o wide
kubectl get pods --watch               # Ctrl+C ends the watch
kubectl logs deployment/vaultcore --all-containers --tail=100
kubectl logs deployment/vaultcore --follow  # Ctrl+C ends the log stream
kubectl get events --sort-by=.lastTimestamp
kubectl describe deployment vaultcore
kubectl describe statefulset postgres
kubectl get pvc
kubectl describe nodes
kubectl exec postgres-0 -- pg_isready -U vaultcore -d vaultcore
kubectl exec -it postgres-0 -- psql -U vaultcore -d vaultcore
kubectl exec deployment/vaultcore -- java -version
```

Pick a pod name from `kubectl get pods` before running these per-pod commands:

```bash
vaultcore_pod=replace-with-a-pod-name-from-the-list
kubectl describe pod "$vaultcore_pod"
kubectl logs "$vaultcore_pod" --previous
kubectl get pod "$vaultcore_pod" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}{"\n"}'
```

Use previous logs for CrashLoopBackOff; inspect events for Pending/ImagePullBackOff
and PVC problems. The termination reason can identify OOMKilled. `kubectl top pods`
and `kubectl top nodes` need Metrics Server, which this project does not install.
To inspect kind's control-plane diagnostics:

```bash
kind export logs /tmp/vaultcore-kind-logs --name vaultcore
```

When finished, stop any port-forward/watch/log commands with Ctrl+C. Deleting the
kind cluster also **deletes its local database/PVC data**:

```bash
kind delete cluster --name vaultcore
kind get clusters
```

### Git, pull request, and CI commands

Create a feature branch from the current main branch:

```bash
git switch main
git pull --ff-only origin main
git switch -c feature/your-change
git status
git diff
git log --oneline -12
git diff --stat main...HEAD
git add README.md docs/architecture.md docs/plan.md
git commit -m "docs: update project run instructions"
git push --set-upstream origin feature/your-change
```

Stage the files you changed; the example stages documentation. Open a pull request
against `main` from the pushed branch. Pushes to `main` and `feature/**`, and all
pull requests, run [GitHub Actions](https://github.com/BAGOMBEKA-JOB-DEV/vaultcore/actions).
The exact verification and tool-download commands are in
[ci.yaml](.github/workflows/ci.yaml).

For commands beyond this project, consult the official
[kubectl quick reference](https://kubernetes.io/docs/reference/kubectl/quick-reference/),
[kind guide](https://kind.sigs.k8s.io/docs/user/quick-start/), and
[Docker Compose reference](https://docs.docker.com/reference/cli/docker/compose/).
