#!/usr/bin/env bash
# Removes the demo: the Helm release and the namespace with everything in it, data and Secrets included.
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
select_cluster
info "removing release camunda and namespace $NS from context $DEMO_CONTEXT"
helm uninstall camunda -n "$NS" --wait 2>/dev/null || true
kubectl delete namespace "$NS" --ignore-not-found --wait=true
