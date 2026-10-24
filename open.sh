#!/usr/bin/env bash
# Publishes the demo's UIs on this machine only: each port-forward binds 127.0.0.1, never a LAN address, so
# Keycloak (including its admin console) and the Camunda UIs are not reachable from the network you are on.
# Leave it running while you present; Ctrl-C stops all of them.
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
select_cluster
pids=()
# Stop each loop and the kubectl it is running, then clean up.
stop() { for p in "${pids[@]}"; do pkill -P "$p" 2>/dev/null || true; kill "$p" 2>/dev/null || true; done; rm -rf "$DEMO_TMP"; }
trap stop EXIT
# A port-forward ends when its pod restarts; each one runs in a loop that reconnects until Ctrl-C.
forward() {
  while true; do
    kubectl -n "$NS" port-forward --address 127.0.0.1 "svc/$1" "$2" >/dev/null 2>&1 || true
    sleep 2
  done &
  pids+=($!)
}
# Refuse to start when a local port is taken, instead of a forward that fails without a word.
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for p in 18080 8080 8084 8070 8085; do
  if port_busy "$p"; then die "port $p on 127.0.0.1 is already in use; stop that process first (is another ./open.sh running?)"; fi
done
forward keycloak 18080:18080
forward camunda-zeebe-gateway 8080:8080
forward camunda-identity 8084:80
forward camunda-web-modeler-restapi 8070:80
forward camunda-web-modeler-websockets 8085:80
echo "Operate/Tasklist http://localhost:8080  Hub http://localhost:8070  Identity http://localhost:8084  Keycloak http://localhost:18080/auth"
echo "Ctrl-C to stop."
wait
