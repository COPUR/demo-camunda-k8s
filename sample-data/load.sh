#!/bin/sh
# Runs inside the demo-loader Job (curl image). Deploys the TR FAST P2 models, starts one P2 instance per synthetic
# payment in scenarios.json, then wakes the waiting ones with Msg_SchemeStatusReported the way the event bridge
# will, so the demo does not wait for the real timers (PT30S, PT2M, PT10M).
set -eu
base="${BPO_DEMO_BASE:-http://camunda:8080}"
# Credentials go through a curl config file, never the command line. The single-pod demo signs in as the presenter
# (basic auth); the microservices demo takes a client-credentials token for demo-loader from Keycloak.
umask 077
trap 'rm -f /tmp/auth.curl' EXIT
if [ -n "${BPO_DEMO_TOKEN_URL:-}" ]; then
  tries=0
  until token=$(curl -sf --cacert /tls/ca.crt -d grant_type=client_credentials -d client_id=demo-loader \
      --data-urlencode "client_secret=$CRED" "$BPO_DEMO_TOKEN_URL" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p') && [ -n "$token" ]; do
    tries=$((tries + 1))
    [ "$tries" -lt 100 ] || { echo "no token from Keycloak" >&2; exit 1; }
    sleep 3
  done
  printf 'header = "Authorization: Bearer %s"\n' "$token" > /tmp/auth.curl
else
  printf 'user = "presenter:%s"\n' "$CRED" > /tmp/auth.curl
fi

# A new pod can start before its network policy is in place; wait until Camunda answers (at most five minutes).
tries=0
until curl -sf -o /dev/null -K /tmp/auth.curl "$base/v2/topology"; do
  tries=$((tries + 1))
  [ "$tries" -lt 100 ] || { echo "Camunda did not answer" >&2; exit 1; }
  sleep 3
done

deploy_args=""
for f in /models/*; do deploy_args="$deploy_args -F resources=@$f"; done
# shellcheck disable=SC2086
curl -sS --fail-with-body -K /tmp/auth.curl -X POST "$base/v2/deployments" $deploy_args > /tmp/deployment.json
echo "deployed: $(grep -o '"processDefinitionId":"[^"]*"' /tmp/deployment.json | tr '\n' ' ')"

ids=$(sed -n 's/^ *"\(demo-[a-z0-9-]*\)".*/\1/p' /data/scenarios.json)
for id in $ids; do
  curl -sS --fail-with-body -K /tmp/auth.curl -H 'content-type: application/json' -X POST "$base/v2/process-instances" \
    -d "{\"processDefinitionId\":\"pay-instant-exception\",\"variables\":{\"paymentId\":\"$id\",\"jurisdiction\":\"TR\",\"rail\":\"FAST\"}}" > /dev/null
  echo "started P2 for $id"
done

# Each wake makes the status library read psg-tr again; three wakes use up the four reads of an unresolved payment.
for round in 1 2 3; do
  sleep 10
  for id in $ids; do
    case "$id" in
      demo-tr-pdng-*|demo-tr-unres-*)
        curl -sS --fail-with-body -K /tmp/auth.curl -H 'content-type: application/json' -X POST "$base/v2/messages/publication" \
          -d "{\"name\":\"Msg_SchemeStatusReported\",\"correlationKey\":\"$id\",\"timeToLive\":0}" > /dev/null
        echo "wake $round: $id" ;;
    esac
  done
done
echo "sample data loaded"
