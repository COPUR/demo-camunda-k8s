#!/bin/sh
# Runs inside the demo-loader Job (curl image). Deploys the TR FAST P2 models, starts one P2 instance per synthetic
# payment in scenarios.json, then wakes the waiting ones with Msg_SchemeStatusReported the way the event bridge
# will, so the demo does not wait for the real timers (PT30S, PT2M, PT10M).
# Then starts the consumer loan saga (loan-personal-saga) for each synthetic application in loans.json and plays the
# customer's part (signature, withdrawal) with idempotent messages.
set -eu
base="${BPO_DEMO_BASE:-http://camunda:8080}"
# Credentials go through a curl config file, never the command line. The single-pod demo signs in as the presenter
# (basic auth); the microservices demo takes a client-credentials token for demo-loader from Keycloak.
umask 077
trap 'rm -f /tmp/auth.curl /tmp/post.json' EXIT
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
# --- Consumer loan saga (loan-personal-saga), synthetic applications in loans.json --------------------------------
# post PATH JSON prints the HTTP status. 409 is fine on a re-run: business id uniqueness refuses a second active
# instance for the same application, and a message with a known messageId is dropped as a duplicate.
post() {
  curl -sS -o /tmp/post.json -w '%{http_code}' -K /tmp/auth.curl -H 'content-type: application/json' -X POST "$base$1" -d "$2"
}
publish() { # publish MESSAGE APPLICATION-ID
  code=$(post /v2/messages/publication \
    "{\"name\":\"$1\",\"correlationKey\":\"$2\",\"messageId\":\"$1:$2\",\"timeToLive\":600000}")
  case "$code" in
    2*) echo "published $1 for $2" ;;
    409) echo "$1 for $2 already published (same messageId), dropped" ;;
    *) cat /tmp/post.json >&2; echo "publishing $1 for $2 failed with $code" >&2; exit 1 ;;
  esac
}
if [ -f /data/loans.json ]; then
  loans=$(sed -n 's/^ *"\(demo-tr-loan-[0-9]*\)".*/\1/p' /data/loans.json)
  for id in $loans; do
    vars=$(sed -n "s/^ *\"$id\": *{\(.*\)},\{0,1\} *$/\1/p" /data/loans.json)
    code=$(post /v2/process-instances \
      "{\"processDefinitionId\":\"loan-personal-saga\",\"businessId\":\"$id\",\"variables\":{\"applicationId\":\"$id\",$vars}}")
    case "$code" in
      2*) echo "started loan saga for $id" ;;
      409) echo "loan saga for $id is already running" ;;
      *) cat /tmp/post.json >&2; echo "starting the loan saga for $id failed with $code" >&2; exit 1 ;;
    esac
  done
  # The customer signs 001, 005 and 006 (004 never signs, 003 waits for the underwriter); 006 then withdraws. The
  # messages wait up to ten minutes for their catch event. The second signature for 001 shows message idempotency.
  for id in demo-tr-loan-001 demo-tr-loan-005 demo-tr-loan-006; do publish Msg_LoanContractSigned "$id"; done
  publish Msg_LoanContractSigned demo-tr-loan-001
  publish Msg_LoanWithdrawn demo-tr-loan-006
fi
echo "sample data loaded"
