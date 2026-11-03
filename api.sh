#!/usr/bin/env bash
# The demo's exception API from this machine, to show a case over REST next to Tasklist (usage below).
# Each run opens its own port-forwards on free 127.0.0.1 ports, so ./open.sh can keep running, takes a
# client-credentials token from Keycloak as one of the demo's API clients and checks both servers' certificates
# against the demo CA. Client secrets and tokens reach curl only through files in a private temporary directory,
# never on a command line, and are not printed. The response body goes to stdout, the status and headers to stderr.
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh

usage() {
  cat >&2 <<'EOF'
usage: ./demo.sh api queue [ops|supervisor]         the cases waiting in a review queue, oldest first
       ./demo.sh api case PAYMENT_ID               the case of one payment, with its open review and ETag
       ./demo.sh api raise PAYMENT_ID              opens a case for a demo-tr-* payment (TR, FAST)
       ./demo.sh api decide PAYMENT_ID SETTLED|REJECTED|ESCALATE
                                                   decides the open review as a reviewer of its queue
       ./demo.sh api catalog [EXC-nnn|PEX-nnnn]    the exception case catalogue, or one case or error code
EOF
  exit 2
}

# The payment id goes into a path and a JSON body, so only the API's own form is sent.
payment_id() {
  [[ "${1:-}" =~ ^demo-tr-[a-z0-9][a-z0-9-]{0,39}$ ]] || die "a payment id is demo-tr- and 1 to 40 of a-z, 0-9 and - (got '${1:-}')"
}

[ $# -ge 1 ] || usage
command=$1
shift
case "$command" in
  queue)   [ $# -le 1 ] || usage; level=${1:-ops}; case "$level" in ops|supervisor) ;; *) usage ;; esac ;;
  case|raise) [ $# -eq 1 ] || usage; payment_id "$1" ;;
  decide)  [ $# -eq 2 ] || usage; payment_id "$1"; decision=$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')
           case "$decision" in SETTLED|REJECTED|ESCALATE) ;; *) usage ;; esac ;;
  catalog) [ $# -le 1 ] || usage
           [ $# -eq 0 ] || [[ "$1" =~ ^(EXC-[0-9]{3}|PEX-[0-9]{4})$ ]] || die "a catalogue code is EXC-nnn or PEX-nnnn (got '$1')" ;;
  *) usage ;;
esac
command -v curl >/dev/null || die "curl is required"

select_cluster
umask 077
pids=()
cleanup() {
  for p in ${pids[@]+"${pids[@]}"}; do kill "$p" 2>/dev/null || true; done
  rm -rf "$DEMO_TMP"
}
trap cleanup EXIT

# forward SERVICE PORT: a port-forward to SERVICE:PORT from a free port on 127.0.0.1, left in $local_port.
forward() {
  local log="$DEMO_TMP/forward-$1.log" pid tries=0
  kubectl -n "$NS" port-forward --address 127.0.0.1 "svc/$1" ":$2" > "$log" 2>&1 &
  pid=$!
  pids+=("$pid")
  until local_port=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) .*/\1/p' "$log" | head -n 1) && [ -n "$local_port" ]; do
    kill -0 "$pid" 2>/dev/null || die "no port-forward to $1: $(tail -n 1 "$log")"
    tries=$((tries + 1))
    [ "$tries" -lt 100 ] || die "no port-forward to $1 after 20 s"
    sleep 0.2
  done
}

kubectl -n "$NS" get secret demo-tls-ca -o 'jsonpath={.data.ca\.crt}' 2>/dev/null | base64 -d > "$DEMO_TMP/ca.crt" || true
[ -s "$DEMO_TMP/ca.crt" ] || die "no demo CA in namespace $NS; run ./demo.sh up first"
forward keycloak 8443
keycloak_port=$local_port
forward payment-exception-api 8443
api_port=$local_port
# Both names resolve to the port-forwards (curl --connect-to), so the certificates are checked for the names they
# were issued to, and a proxy set in the environment is never used for them.
token_url=https://keycloak:8443/auth/realms/camunda-platform/protocol/openid-connect/token
api_base=https://payment-exception-api:8443

# token CLIENT: a client-credentials token for one of the demo's API clients, as the curl config $DEMO_TMP/CLIENT.curl.
token() {
  local secret response access
  secret=$(kubectl -n "$NS" get secret demo-api-clients -o "jsonpath={.data.$1}" 2>/dev/null | base64 -d) || secret=""
  [ -n "$secret" ] || die "no secret for API client $1 in namespace $NS; run ./demo.sh up first"
  response=$(printf 'data-urlencode = "client_secret=%s"\n' "$secret" \
    | curl -sS -f --noproxy '*' --cacert "$DEMO_TMP/ca.crt" --connect-to "keycloak:8443:127.0.0.1:$keycloak_port" -K - \
        -d grant_type=client_credentials -d "client_id=$1" "$token_url") || die "Keycloak gave no token to $1"
  access=$(printf '%s' "$response" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
  [ -n "$access" ] || die "Keycloak's answer for $1 has no access token"
  printf 'header = "Authorization: Bearer %s"\n' "$access" > "$DEMO_TMP/$1.curl"
}

# call CLIENT METHOD PATH [BODY [HEADER...]]: one request to the API as CLIENT. Prints the status and the headers
# that matter on stderr and leaves the answer in $DEMO_TMP/body and $DEMO_TMP/headers; the status is in $status.
call() {
  local client=$1 method=$2 path=$3 body=${4:-}
  shift 3
  [ $# -eq 0 ] || shift
  [ -f "$DEMO_TMP/$client.curl" ] || token "$client"
  local args=(-sS --noproxy '*' --cacert "$DEMO_TMP/ca.crt" --connect-to "payment-exception-api:8443:127.0.0.1:$api_port"
    -K "$DEMO_TMP/$client.curl" -D "$DEMO_TMP/headers" -o "$DEMO_TMP/body" -w '%{http_code}' -X "$method"
    -H 'accept: application/json')
  [ -z "$body" ] || args+=(-H 'content-type: application/json' -d "$body")
  local header
  for header in "$@"; do args+=(-H "$header"); done
  status=$(curl "${args[@]}" "$api_base$path") || die "no answer from the exception API"
  printf '%s %s as %s: %s\n' "$method" "$path" "$client" "$status" >&2
  grep -i -E '^(etag|location|idempotent-replayed|retry-after):' "$DEMO_TMP/headers" | tr -d '\r' | sed 's/^/  /' >&2 || true
}

# Pretty-prints the last answer when jq or python3 is there; an error status (a problem+json body) fails the run.
show() {
  if command -v jq >/dev/null && jq . "$DEMO_TMP/body" 2>/dev/null; then :
  elif command -v python3 >/dev/null && python3 -I -m json.tool "$DEMO_TMP/body" 2>/dev/null; then :
  else cat "$DEMO_TMP/body"; echo; fi
  [ "$status" -lt 400 ]
}

case "$command" in
  queue)
    if [ "$level" = ops ]; then client=demo-ops-reviewer queue=pay-tr-fast-ops review=REVIEW_LEVEL_OPERATIONS
    else client=demo-supervisor-reviewer queue=pay-tr-fast-supervisor review=REVIEW_LEVEL_SUPERVISOR; fi
    call "$client" GET "/api/v1/payment-exceptions?queue=$queue&reviewLevel=$review&pageSize=100"
    show
    echo "$(grep -o '"paymentId":"[^"]*"' "$DEMO_TMP/body" | wc -l | tr -d ' ') cases in $queue" >&2 ;;
  case)
    call demo-ops-reviewer GET "/api/v1/payment-exceptions/$1"
    show ;;
  raise)
    call demo-exception-raiser POST /api/v1/payment-exceptions "{\"paymentId\":\"$1\",\"jurisdiction\":\"TR\",\"rail\":\"RAIL_FAST\"}" \
      "Idempotency-Key: demo-api-$(openssl rand -hex 12)"
    show ;;
  decide)
    # The decision names the review it was made on (If-Match with the case's ETag); a review in the supervisor
    # queue is decided by the supervisor client, any other by the operations reviewer.
    call demo-ops-reviewer GET "/api/v1/payment-exceptions/$1"
    [ "$status" = 200 ] || show
    etag=$(sed -n 's/^[Ee][Tt][Aa][Gg]: *//p' "$DEMO_TMP/headers" | tr -d '\r' | head -n 1)
    [ -n "$etag" ] || die "the case of $1 came without an ETag"
    reviewer=demo-ops-reviewer
    if grep -q '"level":"REVIEW_LEVEL_SUPERVISOR"' "$DEMO_TMP/body"; then reviewer=demo-supervisor-reviewer; fi
    call "$reviewer" POST "/api/v1/payment-exceptions/$1/decisions" "{\"decision\":\"DECISION_$decision\"}" \
      "If-Match: $etag" "Idempotency-Key: demo-api-$(openssl rand -hex 12)"
    show ;;
  catalog)
    case "${1:-}" in
      "")    call demo-ops-reviewer GET /api/v1/exception-catalog ;;
      EXC-*) call demo-ops-reviewer GET "/api/v1/exception-catalog/cases/$1" ;;
      PEX-*) call demo-ops-reviewer GET "/api/v1/exception-catalog/errors/$1" ;;
    esac
    show ;;
esac
