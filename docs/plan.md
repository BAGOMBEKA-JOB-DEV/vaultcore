# VaultCore implementation plan

Deliver a banking transaction API for UGX accounts, money movement, immutable
ledger postings, JWT security, and Kubernetes deployment. Keep one application
and two design documents. The README contains setup, API contracts, and commands.

## Delivered milestones

| Milestone | Delivered |
| --- | --- |
| Foundation | Java 21, Maven Wrapper, Spring Boot, PostgreSQL/Flyway, account creation and queries |
| Money movement | Deposits, withdrawals, transfers, balanced append-only ledger, rollback |
| Correctness | Database locks, durable scoped idempotency, bounded transient retries, reconciliation |
| Security | JWT validation, ownership checks, teller cash operations, auditor access, local development identity |
| Deployment | Multi-stage Docker build, API/database Compose stack, direct Docker commands, native runner, Kubernetes resources/probes/PVC |
| Operations | CI integration tests, Compose startup checks, restart persistence, Kubernetes failure and recovery verification |

## Verification

- Unit/API tests cover exact money handling and bearer authentication.
- PostgreSQL integration tests cover real HTTP, permissions, schema guards,
  matching/conflicting retries, concurrent duplicates, concurrent withdrawals,
  opposing transfers, frozen accounts, balance limits, and reconciliation.
- A database trigger injects a failure after debit; tests verify balance and
  idempotency-claim rollback, then retry the original request successfully.
- Another service instance replays a durable result without shared Java state.
- `scripts/demo.py` and `--verify-state` check process-restart persistence.
- CI builds the image from source, starts Compose, and verifies API operations.
- CI also runs PostgreSQL 17 and `scripts/k8s-smoke.sh` on a disposable kind
  cluster: two replicas, rolling restart, database outage, bad rollout, recovery.

## Future work

1. Add reversal transactions with explicit links to original postings.
2. Extend currency rules and reconciliation for multiple currencies.
3. Add transactional outbox publishing and event consumer deduplication.
4. Measure throughput and contention under representative load.
5. Extend deployment automation for managed identity, database availability,
   backup/restore, and environment-specific infrastructure.

External banking integrations and a frontend are outside the current scope.
