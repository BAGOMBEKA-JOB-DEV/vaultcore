#!/usr/bin/env bash
# Runs only against the explicitly named, disposable VaultCore kind cluster.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ "$(kubectl config current-context)" != kind-vaultcore ]]; then
    printf 'Select the disposable kind-vaultcore context before running this exercise.\n' >&2
    exit 1
fi
exercise_dir="$(mktemp -d /tmp/vaultcore-k8s.XXXXXX)"
forward_pid=""
cleanup() {
    if [[ -n "$forward_pid" ]]; then kill "$forward_pid" 2>/dev/null || true; wait "$forward_pid" 2>/dev/null || true; fi
    rm -rf -- "$exercise_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$exercise_dir/private-key.pem" 2>/dev/null
chmod 600 "$exercise_dir/private-key.pem"
database_password="$(openssl rand -hex 24)"
kubectl create secret generic vaultcore-secrets --from-literal=DB_PASSWORD="$database_password"
kubectl create secret generic vaultcore-dev-key --from-file=private-key.pem="$exercise_dir/private-key.pem"
kubectl apply -f deploy/k8s/postgres.yaml -f deploy/k8s/app.yaml
kubectl patch deployment vaultcore --type=strategic --patch-file deploy/k8s/ci-patch.yaml
kubectl rollout status statefulset/postgres --timeout=180s
kubectl rollout status deployment/vaultcore --timeout=240s

start_forward() {
    local target="${1:-service/vaultcore}"
    kubectl port-forward "$target" 18080:8080 --address=127.0.0.1 > "$exercise_dir/forward.log" 2>&1 &
    forward_pid=$!
    for attempt in {1..40}; do
        if curl --fail --silent http://127.0.0.1:18080/actuator/health/readiness >/dev/null; then return; fi
        sleep 1
    done
    cat "$exercise_dir/forward.log" >&2
    return 1
}
stop_forward() {
    kill "$forward_pid" 2>/dev/null || true
    wait "$forward_pid" 2>/dev/null || true
    forward_pid=""
}
mapfile -t api_pods < <(kubectl get pods -l app=vaultcore -o json | python3 -c '
import json, sys
for pod in json.load(sys.stdin)["items"]:
    ready = any(condition["type"] == "Ready" and condition["status"] == "True"
                for condition in pod.get("status", {}).get("conditions", []))
    if ready and not pod["metadata"].get("deletionTimestamp"):
        print(pod["metadata"]["name"])
')
if [[ ${#api_pods[@]} -ne 2 ]]; then
    kubectl get pods -l app=vaultcore
    printf 'Expected two ready, non-terminating API replicas.\n' >&2
    exit 1
fi
start_forward "pod/${api_pods[0]}"
python3 scripts/demo.py --base-url http://127.0.0.1:18080 --state "$exercise_dir/state.json"
stop_forward
start_forward "pod/${api_pods[1]}"
python3 scripts/demo.py --base-url http://127.0.0.1:18080 --verify-state "$exercise_dir/state.json"
stop_forward
kubectl create job vaultcore-availability --image=vaultcore:dev -- /bin/sh -c \
    'for attempt in $(seq 1 60); do wget -q -T 3 -O /dev/null http://vaultcore:8080/actuator/health/readiness || exit 1; sleep 1; done'
kubectl wait pod -l job-name=vaultcore-availability --for=condition=Ready --timeout=60s
kubectl rollout restart deployment/vaultcore
kubectl rollout status deployment/vaultcore --timeout=180s
kubectl wait job/vaultcore-availability --for=condition=Complete --timeout=120s
start_forward
python3 scripts/demo.py --base-url http://127.0.0.1:18080 --verify-state "$exercise_dir/state.json"
stop_forward

# Readiness should fail during a database outage while liveness remains independent.
kubectl scale statefulset postgres --replicas=0
kubectl wait pod -l app=vaultcore --for=condition=Ready=false --timeout=90s
kubectl scale statefulset postgres --replicas=1
kubectl rollout status statefulset/postgres --timeout=180s
kubectl wait pod -l app=vaultcore --for=condition=Ready --timeout=180s

# Diagnose a bad image rollout, then recover to the previously working image.
kubectl set image deployment/vaultcore api=vaultcore:intentionally-missing
if kubectl rollout status deployment/vaultcore --timeout=20s; then
    printf 'Expected the missing image rollout to fail.\n' >&2
    exit 1
fi
kubectl get pods
kubectl get events --sort-by=.lastTimestamp
kubectl rollout undo deployment/vaultcore
kubectl rollout status deployment/vaultcore --timeout=180s
start_forward
python3 scripts/demo.py --base-url http://127.0.0.1:18080 --verify-state "$exercise_dir/state.json"
printf 'PASS: two replicas, restart persistence, database recovery, and failed rollout recovery.\n'
