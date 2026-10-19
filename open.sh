#!/usr/bin/env bash
# Publishes the demo's UIs on this machine only: each port-forward binds 127.0.0.1, never a LAN address, so
# Keycloak (including its admin console) and the Camunda UIs are not reachable from the network you are on.
# Leave it running while you present; Ctrl-C stops all of them.
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
select_cluster
pids=()
forward() { kubectl -n "$NS" port-forward --address 127.0.0.1 "svc/$1" "$2" >/dev/null & pids+=($!); }
trap 'kill "${pids[@]}" 2>/dev/null; rm -rf "$DEMO_TMP"' EXIT
forward keycloak 18080:18080
forward camunda-zeebe-gateway 8080:8080
forward camunda-identity 8084:80
forward camunda-web-modeler-restapi 8070:80
forward camunda-web-modeler-websockets 8085:80
echo "Operate/Tasklist http://localhost:8080  Hub http://localhost:8070  Identity http://localhost:8084  Keycloak http://localhost:18080/auth"
echo "Ctrl-C to stop."
wait
