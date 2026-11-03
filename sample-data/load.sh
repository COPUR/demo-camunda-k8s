#!/bin/sh
# Runs inside the demo-loader Job (curl image). Deploys the TR FAST P2 models, starts one P2 instance per synthetic
# payment in scenarios.json, then wakes the waiting ones with Msg_SchemeStatusReported the way the event bridge
# will, so the demo does not wait for the real timers (PT30S, PT2M, PT10M).
# Each payment's instance carries the payment id as its business id (Operate filters on it) and the scenario as tags,
# and a payment that already has an instance is skipped, so running the loader again adds no second case.
# BPO_DEMO_MODELS, BPO_DEMO_DATA, BPO_DEMO_TMP and BPO_DEMO_WAKE_ROUNDS exist for models/e2e/run.sh.
set -eu
base="${BPO_DEMO_BASE:-http://camunda:8080}"
models="${BPO_DEMO_MODELS:-/models}"
data="${BPO_DEMO_DATA:-/data}"
tmp="${BPO_DEMO_TMP:-/tmp}"
rounds="${BPO_DEMO_WAKE_ROUNDS:-3}"
# Credentials go through a curl config file, never the command line. The single-pod demo signs in as the presenter
# (basic auth); the microservices demo takes a client-credentials token for demo-loader from Keycloak.
umask 077
trap 'rm -f "$tmp/auth.curl" "$tmp/started.json" "$tmp/deployment.json"' EXIT
if [ -n "${BPO_DEMO_TOKEN_URL:-}" ]; then
  tries=0
  until token=$(curl -sf --cacert /tls/ca.crt -d grant_type=client_credentials -d client_id=demo-loader \
      --data-urlencode "client_secret=$CRED" "$BPO_DEMO_TOKEN_URL" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p') && [ -n "$token" ]; do
    tries=$((tries + 1))
    [ "$tries" -lt 100 ] || { echo "no token from Keycloak" >&2; exit 1; }
    sleep 3
  done
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$tmp/auth.curl"
else
  printf 'user = "presenter:%s"\n' "$CRED" > "$tmp/auth.curl"
fi

# A new pod can start before its network policy is in place; wait until Camunda answers (at most five minutes).
tries=0
until curl -sf -o /dev/null -K "$tmp/auth.curl" "$base/v2/topology"; do
  tries=$((tries + 1))
  [ "$tries" -lt 100 ] || { echo "Camunda did not answer" >&2; exit 1; }
  sleep 3
done

deploy_args=""
for f in "$models"/*; do if [ -f "$f" ]; then deploy_args="$deploy_args -F resources=@$f"; fi; done
# shellcheck disable=SC2086
curl -sS --fail-with-body -K "$tmp/auth.curl" -X POST "$base/v2/deployments" $deploy_args > "$tmp/deployment.json"
echo "deployed: $(grep -o '"processDefinitionId":"[^"]*"' "$tmp/deployment.json" | tr '\n' ' ')"

ids=$(sed -n 's/^ *"\(demo-[a-z0-9-]*\)".*/\1/p' "$data/scenarios.json")
for id in $ids; do
  # demo-tr-unres-001 -> scenario:unres
  scenario=$(printf '%s' "$id" | sed -n 's/^demo-tr-\([a-z]*\)-[0-9]*$/\1/p')
  [ -n "$scenario" ] || { echo "unexpected sample payment id $id" >&2; exit 1; }
  if curl -sS --fail-with-body -K "$tmp/auth.curl" -H 'content-type: application/json' -X POST "$base/v2/process-instances/search" \
      -d "{\"filter\":{\"processDefinitionId\":\"pay-instant-exception\",\"businessId\":\"$id\"},\"page\":{\"limit\":1}}" \
      | grep -q "\"businessId\":\"$id\""; then
    echo "P2 for $id already exists; skipped"
    continue
  fi
  # 409: an active instance with this business id exists (business id uniqueness on) but is not searchable yet.
  code=$(curl -sS -K "$tmp/auth.curl" -o "$tmp/started.json" -w '%{http_code}' -H 'content-type: application/json' \
    -X POST "$base/v2/process-instances" \
    -d "{\"processDefinitionId\":\"pay-instant-exception\",\"businessId\":\"$id\",\"tags\":[\"demo\",\"scenario:$scenario\",\"jurisdiction:TR\",\"rail:FAST\"],\"variables\":{\"paymentId\":\"$id\",\"jurisdiction\":\"TR\",\"rail\":\"FAST\"}}")
  case "$code" in
    2??) echo "started P2 for $id" ;;
    409) echo "P2 for $id is already active; skipped" ;;
    *)   echo "starting P2 for $id answered $code: $(head -c 400 "$tmp/started.json")" >&2; exit 1 ;;
  esac
done

# Each wake makes the status library read psg-tr again; three wakes use up the four reads of an unresolved payment.
round=0
while [ "$round" -lt "$rounds" ]; do
  round=$((round + 1))
  sleep 10
  for id in $ids; do
    case "$id" in
      demo-tr-pdng-*|demo-tr-unres-*)
        curl -sS --fail-with-body -K "$tmp/auth.curl" -H 'content-type: application/json' -X POST "$base/v2/messages/publication" \
          -d "{\"name\":\"Msg_SchemeStatusReported\",\"correlationKey\":\"$id\",\"timeToLive\":0}" > /dev/null
        echo "wake $round: $id" ;;
    esac
  done
done
echo "sample data loaded"
