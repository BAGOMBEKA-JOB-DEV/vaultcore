# VaultCore plan and completion record

Deliver a practice banking API for simulated UGX money, with Java/Spring Boot,
PostgreSQL, JWT security, and Kubernetes operations. Keep one application and two
documents. The README contains setup, API contracts, and run commands.

## Completed implementation milestones

| Milestone | Delivered |
| --- | --- |
| Foundation | Java 21, Maven Wrapper, Spring Boot, PostgreSQL/Flyway, account creation and queries |
| Money movement | Deposits, withdrawals, transfers, balanced append-only ledger, rollback |
| Correctness | Database locks, durable scoped idempotency, bounded transient retries, reconciliation |
| Security | JWT validation, ownership checks, teller cash operations, auditor access, local demo identity |
| Deployment | Native low-memory runner, Compose, pinned image, Kubernetes resources/probes/PVC |
| Operations | CI verification, demo/restart checks, Kubernetes failure and recovery exercises |

## Evidence

- Unit/API tests cover exact money handling and bearer authentication.
- PostgreSQL integration tests exercise real HTTP, permissions, schema guards,
  matching/conflicting retries, concurrent duplicates, concurrent withdrawals,
  opposing transfers, frozen accounts, balance limits, and ledger reconciliation.
- A database trigger injects a failure after debit; the test proves balance and
  idempotency-claim rollback, then retries the original request successfully.
- Another service instance replays the durable result without shared Java state.
- `scripts/demo.py` and `--verify-state` exercise actual process-restart persistence.
- CI runs PostgreSQL 17 and `scripts/k8s-smoke.sh` on a disposable kind cluster:
  two replicas, rolling restart, database outage, bad image rollout, recovery.
- The local machine uses native PostgreSQL 16 for bounded tests; no Kubernetes
  cluster is started locally. CI status is the evidence for live cluster checks.

## Practice sequence

Read account creation first, then trace a deposit through MoneyService and the
SQL migration. Run duplicate requests, then concurrent withdrawals. Examine the
rollback test. Review JWT identity versus role/ownership rules. Finally use a
larger machine or CI for Kubernetes failures and recovery. Explain each invariant
and the evidence that proves it before adding another feature.

## Optional extensions

Kafka plus transactional outbox/consumer deduplication, reversal transactions,
multiple currencies, external banking integrations, frontend, load testing, and
production identity/infrastructure hardening are outside this practice MVP.
