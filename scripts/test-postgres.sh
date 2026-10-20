#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
postgres_bin="${VAULTCORE_POSTGRES_BIN:-}"
if [[ -z "$postgres_bin" ]]; then
    for candidate in /usr/lib/postgresql/*/bin; do
        if [[ -x "$candidate/initdb" ]]; then postgres_bin="$candidate"; fi
    done
fi
if [[ ! -x "$postgres_bin/initdb" ]]; then
    printf 'PostgreSQL server tools required. Alternatively use ./scripts/mvn.sh -Pintegration verify with Docker.\n' >&2
    exit 1
fi
test_dir="$(mktemp -d /tmp/vaultcore-postgres.XXXXXX)"
cleanup() {
    "$postgres_bin/pg_ctl" -D "$test_dir/data" -m immediate -w stop >/dev/null 2>&1 || true
    rm -rf -- "$test_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 -c 'import secrets; print(secrets.token_urlsafe(24))' > "$test_dir/password"
chmod 600 "$test_dir/password"
test_port="$(python3 - <<'PY'
import socket
with socket.socket() as listener:
    listener.bind(('127.0.0.1', 0))
    print(listener.getsockname()[1])
PY
)"
"$postgres_bin/initdb" -D "$test_dir/data" -U vaultcore --pwfile="$test_dir/password" \
    --auth-local=trust --auth-host=scram-sha-256 --no-locale --encoding=UTF8 > "$test_dir/init.log"
# Durability is disabled only for this disposable test database.
"$postgres_bin/pg_ctl" -D "$test_dir/data" -l "$test_dir/server.log" -w start \
    -o "-h 127.0.0.1 -p $test_port -k $test_dir -c shared_buffers=16MB -c max_connections=12 -c fsync=off" >/dev/null
export VAULTCORE_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$test_port/postgres"
export VAULTCORE_TEST_DB_USER=vaultcore
export VAULTCORE_TEST_DB_PASSWORD="$(cat "$test_dir/password")"
printf 'Testing with one disposable PostgreSQL instance on port %s; cleanup is automatic.\n' "$test_port"
"$project_dir/scripts/mvn.sh" --batch-mode -Pintegration verify "$@"
