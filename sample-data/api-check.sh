#!/bin/sh
# Runs inside the demo-api-check Job (curl image) after the sample data is loaded, as install.sh's last check: the
# exception API answers with the demo's real Keycloak tokens and reads the cases from Camunda as its own client.
# Read-only, so the cases stay as the loader left them for the presenter.
set -eu
api="${BPO_API_BASE:-https://payment-exception-api:8443}"
# Client secrets go to curl as a config on stdin and tokens as a config file, never on a command line.
umask 077
trap 'rm -f /tmp/raiser.curl /tmp/ops.curl /tmp/none.curl /tmp/body.json' EXIT

# token CLIENT SECRET FILE: a client-credentials token for CLIENT, written to FILE as a curl Authorization header.
token() {
  tries=0
  until t=$(printf 'data-urlencode = "client_secret=%s"\n' "$2" | curl -sf --cacert /tls/ca.crt -K - \
      -d grant_type=client_credentials -d "client_id=$1" "$BPO_DEMO_TOKEN_URL" \
      | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p') && [ -n "$t" ]; do
    tries=$((tries + 1))
    [ "$tries" -lt 40 ] || { echo "no token from Keycloak for $1" >&2; exit 1; }
    sleep 3
  done
  printf 'header = "Authorization: Bearer %s"\n' "$t" > "$3"
}

# expect STATUS AUTH METHOD PATH [BODY]: one call to the API, which must answer STATUS; the body lands in body.json.
expect() {
  want=$1 auth=$2 method=$3 path=$4 body=${5:-}
  if [ -n "$body" ]; then
    got=$(curl -sS --cacert /tls/ca.crt -K "$auth" -o /tmp/body.json -w '%{http_code}' -X "$method" \
      -H 'content-type: application/json' -H "Idempotency-Key: api-check-$(date +%s)-$$" -H 'If-Match: "rt-0"' \
      -d "$body" "$api$path")
  else
    got=$(curl -sS --cacert /tls/ca.crt -K "$auth" -o /tmp/body.json -w '%{http_code}' -X "$method" "$api$path")
  fi
  [ "$got" = "$want" ] || { echo "$method $path answered $got, expected $want: $(head -c 400 /tmp/body.json)" >&2; exit 1; }
}

token demo-exception-raiser "$RAISER_SECRET" /tmp/raiser.curl
token demo-ops-reviewer "$OPS_REVIEWER_SECRET" /tmp/ops.curl
: > /tmp/none.curl

# A new pod can start before its network policy is in place; wait until the API answers (at most three minutes).
tries=0
until curl -sf -o /dev/null --cacert /tls/ca.crt -K /tmp/raiser.curl "$api/api/v1/exception-catalog"; do
  tries=$((tries + 1))
  [ "$tries" -lt 60 ] || { echo "the exception API did not answer" >&2; exit 1; }
  sleep 3
done

expect 401 /tmp/none.curl GET /api/v1/exception-catalog
expect 200 /tmp/raiser.curl GET /api/v1/exception-catalog
cases=$(grep -o '"code":"EXC-[0-9]*"' /tmp/body.json | wc -l | tr -d ' ')
expect 200 /tmp/ops.curl GET "/api/v1/payment-exceptions?queue=pay-tr-fast-ops&reviewLevel=REVIEW_LEVEL_OPERATIONS&pageSize=50"
queued=$(grep -o '"paymentId":"[^"]*"' /tmp/body.json | wc -l | tr -d ' ')
expect 200 /tmp/ops.curl GET /api/v1/payment-exceptions/demo-tr-unres-001
state=$(sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p' /tmp/body.json)
expect 403 /tmp/raiser.curl POST /api/v1/payment-exceptions/demo-tr-unres-001/decisions '{"decision":"DECISION_SETTLED"}'
echo "exception API verified: 401 without a token, catalogue of $cases cases, $queued cases in queue pay-tr-fast-ops, demo-tr-unres-001 $state, the raiser may not decide (403)"
