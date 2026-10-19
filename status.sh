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
