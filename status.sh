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
# 127.0.0.1; the presenter password goes through a curl config on stdin, never an argv. Best effort: if the API
# does not answer, the table is skipped.
info "payments: expected vs actual (P2 process instances)"
port=18099
kubectl -n "$NS" port-forward --address 127.0.0.1 svc/camunda-zeebe-gateway "$port:8080" >/dev/null 2>&1 &
pf=$!
trap 'kill "$pf" 2>/dev/null; rm -rf "$DEMO_TMP"' EXIT
api() { # api PATH JSON-BODY
  printf 'user = "presenter:%s"\n' "$(kubectl -n "$NS" get secret demo-presenter -o jsonpath='{.data.password}' | base64 -d)" \
    | curl -sf -m 10 -K - -H 'content-type: application/json' -X POST "http://127.0.0.1:$port$1" -d "$2"
}
for _ in 1 2 3 4 5 6 7 8 9 10; do
  vars=$(api /v2/variables/search '{"filter":{"name":"paymentId"},"page":{"limit":100}}') && break
  sleep 1
done
if [ -z "${vars:-}" ]; then
  warn "Camunda did not answer on 127.0.0.1:$port; skipping the payments table"
else
  insts=$(api /v2/process-instances/search '{"filter":{"processDefinitionId":"pay-instant-exception"},"page":{"limit":100}}' || true)
  printf '%-20s %-34s %s\n' PAYMENT EXPECTED ACTUAL
  while read -r id; do
    case "$id" in
      demo-tr-acsc-*|demo-tr-pdng-*) expected="settled (COMPLETED)" ;;
      demo-tr-rjct-*)                expected="rejected (COMPLETED)" ;;
      *)                             expected="waits for an ops decision (ACTIVE)" ;;
    esac
    # The variable's processInstanceKey, then that instance's state.
    key=$(printf '%s' "$vars" | tr '{' '\n' | grep "\"value\":\"\\\\\"$id\\\\\"\"" | sed -n 's/.*"processInstanceKey":"\{0,1\}\([0-9]*\).*/\1/p' | head -1)
    state=$(printf '%s' "$insts" | tr '{' '\n' | grep "\"processInstanceKey\":\"\{0,1\}$key\"\{0,1\}[,}]" | sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p' | head -1)
    printf '%-20s %-34s %s\n' "$id" "$expected" "${state:-not found}"
  done < <(sed -n 's/^ *"\(demo-[a-z0-9-]*\)".*/\1/p' sample-data/scenarios.json)
fi
