#!/usr/bin/env bash
# Health of the demo: pods, the sample-data loader, what each simulator has served, and recent worker errors.
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
select_cluster
info "pods in $NS (context $DEMO_CONTEXT)"
kubectl -n "$NS" get pods
info "sample-data loader"
kubectl -n "$NS" logs job/demo-loader 2>/dev/null | tail -2 || echo "loader has not run"
info "simulators (calls served, holds captured)"
for s in sim-psg-tr sim-acc sim-pay sim-ntf; do
  printf '%-11s %s\n' "$s" "$(kubectl -n "$NS" logs "deploy/$s" 2>/dev/null | grep -o 'calls=[0-9]* holdsCaptured=[0-9]*' | tail -1)"
done
info "worker warnings and errors (last 5)"
kubectl -n "$NS" logs deploy/bpo-worker-payments 2>/dev/null | grep -E ' (WARN|ERROR) ' | tail -5 || true

# Expected vs actual per synthetic payment. Reads the process instances through a short-lived port-forward on
# 127.0.0.1; the presenter password goes through a curl config on stdin, never an argv.
# Exit code: 0 when every payment is as expected, 1 when one is missing or in another state, 2 when Camunda does
# not answer (nothing could be checked).
info "payments: expected vs actual (P2 process instances)"
port=${DEMO_STATUS_PORT:-18099}
kubectl -n "$NS" port-forward --address 127.0.0.1 svc/camunda-zeebe-gateway "$port:8080" >/dev/null 2>&1 &
pf=$!
trap 'kill "$pf" 2>/dev/null; rm -rf "$DEMO_TMP"' EXIT
api() { # api PATH JSON-BODY
  printf 'user = "presenter:%s"\n' "$(kubectl -n "$NS" get secret demo-presenter -o jsonpath='{.data.password}' | base64 -d)" \
    | curl -sf -m 10 -K - -H 'content-type: application/json' -X POST "http://127.0.0.1:$port$1" -d "$2"
}
for _ in $(seq "${DEMO_STATUS_TRIES:-10}"); do
  vars=$(api /v2/variables/search '{"filter":{"name":"paymentId"},"page":{"limit":100}}') && break
  sleep 1
done
if [ -z "${vars:-}" ]; then
  warn "Camunda did not answer on 127.0.0.1:$port; the payments could not be checked"
  exit 2
fi
insts=$(api /v2/process-instances/search '{"filter":{"processDefinitionId":"pay-instant-exception"},"page":{"limit":100}}' || true)
payments_table sample-data/scenarios.json "$vars" "$insts" || exit 1
