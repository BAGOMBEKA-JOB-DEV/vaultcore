# VaultCore

A complete practice banking API for Java/Spring Boot, PostgreSQL transactions,
security, concurrency, and Kubernetes operations. Uses simulated money and one
currency, UGX, with a fixed two-decimal practice model. Java 21 / Spring Boot 4.1.1.

Start with [local development](#start-on-a-4-gb-machine), [verification](#verify),
and the [API contract](#api). The [command reference](#command-reference) below
covers manual API calls, databases, Docker, Kubernetes, failure drills, cleanup,
and Git. Run commands from the repository root. Choose one runtime at a time;
these sections are alternatives and exercises, not one script to paste and run.

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

## Command reference

This is the project operating reference. It covers the supported workflows and
the Kubernetes operations used by CI, rather than every possible kubectl command
or the temporary shell commands used while developing the project. Installer
download/checksum details remain in [setup.sh](scripts/setup.sh); exact CI commands
are in [ci.yaml](.github/workflows/ci.yaml) and [k8s-smoke.sh](scripts/k8s-smoke.sh).

### Tools, build, and local runtime

```bash
./scripts/setup.sh
export PATH="$HOME/.local/bin:$PATH"
java -version
./scripts/mvn.sh --version
psql --version
docker --version
docker compose version
kubectl version --client
kind version
free -h
```

Setup needs Git, Python 3, curl, and archive utilities. Native development/testing
also needs PostgreSQL server tools; the Kubernetes exercise needs Docker and
OpenSSL. These OS prerequisites are separate from the Java/CLI installer. On
another Ubuntu 24.04 x86_64 machine, install missing native prerequisites with:

```bash
sudo apt-get update
sudo apt-get install git python3 curl unzip openssl postgresql
```

The PostgreSQL package can start a system database. The project runners create
their own database instance; use the already-installed tools on this development
machine. For Docker installation on another host, follow the
[official Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/).

Choose the build/test command you need:

```bash
./scripts/mvn.sh verify
./scripts/test-postgres.sh
MAVEN_OPTS='-XX:MaxMetaspaceSize=384m' ./scripts/mvn.sh -Pintegration verify
./scripts/mvn.sh -Dtest=MoneyTest test
./scripts/test-postgres.sh -Dit.test=DatabaseIT
./scripts/mvn.sh clean verify
```

`clean` removes generated `target/` output. Tests create reports under
`target/surefire-reports` and `target/failsafe-reports`. Run a single verification
mode at a time. To run or restart the application:

```bash
python3 scripts/dev.py --build          # verify/build, then start native DB + API
python3 scripts/dev.py                  # reuse the built JAR
./scripts/run.sh --spring.profiles.active=dev  # API only; database must already be running
./scripts/run.sh                        # normal mode; configure external JWT issuer and database
python3 scripts/dev.py --help
python3 scripts/demo.py --help
```

Stop the native runner with Ctrl+C before starting another runtime. Optional
overrides select ports/data/tools without changing the system database:

```bash
SERVER_PORT=8081 VAULTCORE_DEV_DB_PORT=55433 python3 scripts/dev.py
VAULTCORE_DEV_DATA_DIR="$HOME/.local/share/vaultcore/practice-two" python3 scripts/dev.py
VAULTCORE_POSTGRES_BIN=/usr/lib/postgresql/16/bin ./scripts/test-postgres.sh
```

The second data-directory example still uses the configured database port; run it
after stopping the first instance. Native data-directory overrides belong to the
native runner; Compose and Kubernetes each manage their own storage.

### Manual API commands

These examples use the local dev profile. Keep the API running in a separate
terminal. For a forwarded private Kubernetes demo, change the URL to
`http://127.0.0.1:18080`. Normal mode requires tokens from your external provider
instead of `/dev/token`.

Create unique practice identities and keep tokens in shell variables:

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
exercise use `--state /tmp/vaultcore-demo.json`, restart the app, then use
`--verify-state /tmp/vaultcore-demo.json`. Add `--base-url` when using another port.

### Docker, database inspection, and shutdown

Stop the native runner before using the default Compose port. Compose commands:

```bash
docker compose config --quiet
docker compose up postgres             # foreground; leave this terminal open
```

In another terminal:

```bash
docker compose ps
docker compose logs --tail=100 postgres
docker compose logs --follow postgres  # Ctrl+C ends the log stream
docker compose exec postgres pg_isready -U vaultcore -d vaultcore
docker compose exec postgres psql -U vaultcore -d vaultcore
```

While the native development runner is active, inspect its database through its
private Unix socket instead:

```bash
psql --host="${XDG_DATA_HOME:-$HOME/.local/share}/vaultcore/postgres" \
  --port=55432 --username=vaultcore --dbname=vaultcore
```

Adjust the socket/port if you selected custom native settings. Inside either
psql session, these commands inspect data without modifying it:

```sql
\dt
SELECT id, account_type, currency, balance, status FROM accounts;
SELECT operation, count(*) FROM bank_transactions GROUP BY operation;
SELECT transaction_id, sum(amount), count(*) FROM ledger_entries
GROUP BY transaction_id HAVING sum(amount) <> 0 OR count(*) <> 2;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
\q
```

The ledger query should return zero rows. Build or inspect the API image only
after producing the JAR:

```bash
./scripts/mvn.sh verify
docker build -t vaultcore:dev .
docker image inspect vaultcore:dev
docker compose stop                   # stop DB, retain its volume
docker compose down                   # remove Compose containers/network, retain volume
```

`docker compose down --volumes` additionally **deletes Compose database data**;
use it only when you deliberately want a fresh disposable database. Stopping the
native runner preserves native data, and `mvn clean` does not delete any database.

### Kubernetes cluster and deployment

Use a machine with sufficient RAM for these exercises. First build the image
as above, then create/select the disposable cluster:

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

**Automated practice path:** in that fresh cluster, run:

```bash
./scripts/k8s-smoke.sh
```

It creates Secrets/deployments itself and runs the complete CI exercise. Do not
pre-create the same Secrets with the manual path. It leaves the practice cluster
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

For a **private disposable demo cluster** without an external issuer, the manual
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
python3 scripts/demo.py --base-url http://127.0.0.1:18080 --state /tmp/vaultcore-k8s-demo.json
```

The demo requires the private dev-profile setup. Normal-mode API calls require
your issuer's tokens. A pod restart can close a port-forward connection: stop it
and run the forwarding command again before verifying persisted demo state.

### Kubernetes scaling, rollout, and recovery drills

Run these only against the disposable `kind-vaultcore` context. Two-replica dev
authentication requires the shared key above. The normal external-issuer mode
uses its external verification keys. Scale and practise a rolling restart:

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

Reconnect port-forwarding, then verify the earlier demo's state:

```bash
python3 scripts/demo.py --base-url http://127.0.0.1:18080 --verify-state /tmp/vaultcore-k8s-demo.json
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
kind cluster also **deletes its local practice database/PVC data**:

```bash
kind delete cluster --name vaultcore
kind get clusters
```

### Git, pull request, and CI commands

```bash
git switch setup/vaultcore-foundation
git status
git diff
git log --oneline -12
git diff --stat main...HEAD
git rev-list --count main..HEAD
git add README.md docs/architecture.md docs/plan.md
git commit -m "docs: update project run instructions"
git push origin setup/vaultcore-foundation
```

Stage the specific files you changed before committing; the example stages docs.
The open review is [PR #1](https://github.com/BAGOMBEKA-JOB-DEV/vaultcore/pull/1).
Pushes to this branch update that PR and trigger GitHub Actions; monitor runs in
[Actions](https://github.com/BAGOMBEKA-JOB-DEV/vaultcore/actions). The workflow's
larger JVM budget and tool-download/checksum commands are recorded in
[ci.yaml](.github/workflows/ci.yaml). The labelled empty history-marker commits
are historical metadata, not steps required to run or extend the application.

For commands beyond this project, consult the official
[kubectl quick reference](https://kubernetes.io/docs/reference/kubectl/quick-reference/),
[kind guide](https://kind.sigs.k8s.io/docs/user/quick-start/), and
[Docker Compose reference](https://docs.docker.com/reference/cli/docker/compose/).
