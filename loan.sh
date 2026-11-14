#!/usr/bin/env bash
# The consumer loan saga demo (loan-personal-saga, README "İhtiyaç kredisi: saga ve outbox"):
#   ./demo.sh loan                    expected vs actual state of each synthetic application, with its outbox
#   ./demo.sh loan sign <id>          the customer signs the contract (Msg_LoanContractSigned)
#   ./demo.sh loan withdraw <id>      the customer withdraws within the withdrawal period (Msg_LoanWithdrawn)
# Talks to the orchestration REST API through a short-lived port-forward on 127.0.0.1, as the presenter; the
# password goes through a curl config on stdin, never an argv. Only demo-tr-loan-* ids are accepted.
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh

cmd="${1:-status}"
id="${2:-}"
case "$cmd" in
  status) ;;
  sign|withdraw)
    [[ "$id" =~ ^demo-tr-loan-[0-9]{3}$ ]] || die "usage: ./demo.sh loan $cmd demo-tr-loan-NNN" ;;
  *) die "usage: ./demo.sh loan [status | sign <id> | withdraw <id>]" ;;
esac

select_cluster
port=18098
kubectl -n "$NS" port-forward --address 127.0.0.1 svc/camunda-zeebe-gateway "$port:8080" >/dev/null 2>&1 &
pf=$!
trap 'kill "$pf" 2>/dev/null; rm -rf "$DEMO_TMP"' EXIT
cred() { printf 'user = "presenter:%s"\n' "$(kubectl -n "$NS" get secret demo-presenter -o jsonpath='{.data.password}' | base64 -d)"; }
api() { # api PATH JSON-BODY
  cred | curl -sf -m 10 -K - -H 'content-type: application/json' -X POST "http://127.0.0.1:$port$1" -d "$2"
}
ready=false
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if cred | curl -sf -m 5 -K - -o /dev/null "http://127.0.0.1:$port/v2/topology"; then ready=true; break; fi
  sleep 1
done
[ "$ready" = true ] || die "Camunda did not answer on 127.0.0.1:$port; is the demo up (./demo.sh status)?"

publish() { # publish MESSAGE: one messageId per message and application, so a repeat is dropped by the engine
  local code
  code=$(cred | curl -s -m 10 -o /dev/null -w '%{http_code}' -K - -H 'content-type: application/json' -X POST \
      "http://127.0.0.1:$port/v2/messages/publication" \
      -d "{\"name\":\"$1\",\"correlationKey\":\"$id\",\"messageId\":\"$1:$id\",\"timeToLive\":600000}")
  case "$code" in
    2*)  info "$1 published for $id; it waits up to 10 minutes for the process to be ready for it" ;;
    409) warn "$1 was already published for $id; the engine dropped the repeat (same messageId)" ;;
    *)   die "publishing $1 for $id failed with HTTP $code" ;;
  esac
}

case "$cmd" in
  sign)     publish Msg_LoanContractSigned; exit 0 ;;
  withdraw) publish Msg_LoanWithdrawn; exit 0 ;;
esac

# The value of one root-scope variable of an instance, JSON-decoded for strings and numbers.
var() { # var INSTANCE-KEY NAME
  api /v2/variables/search "{\"filter\":{\"processInstanceKey\":\"$1\",\"scopeKey\":\"$1\",\"name\":\"$2\"}}" 2>/dev/null \
    | sed -n 's/.*"value":"\(\\"\)\{0,1\}\([^"\\]*\)\(\\"\)\{0,1\}".*/\2/p' | head -1
}
info "consumer loan saga: expected vs actual (loan-personal-saga process instances)"
printf '%-17s %-44s %-10s %-12s %s\n' APPLICATION EXPECTED STATE OUTCOME "OUTBOX (relayed/written)"
while read -r app; do
  case "$app" in
    demo-tr-loan-001) expected="ACTIVE after the withdrawal period (3 min)" ;;
    demo-tr-loan-002) expected="DECLINED by the credit decision" ;;
    demo-tr-loan-003) expected="waits for the underwriter in Tasklist" ;;
    demo-tr-loan-004) expected="ROLLED_BACK: not signed in 1 min" ;;
    demo-tr-loan-005) expected="ROLLED_BACK: core banking rejected" ;;
    demo-tr-loan-006) expected="WITHDRAWN: whole saga compensated" ;;
    *)                expected="-" ;;
  esac
  inst=$(api /v2/process-instances/search \
    "{\"filter\":{\"processDefinitionId\":\"loan-personal-saga\",\"businessId\":\"$app\"},\"sort\":[{\"field\":\"startDate\",\"order\":\"DESC\"}],\"page\":{\"limit\":1}}" || true)
  key=$(printf '%s' "$inst" | sed -n 's/.*"processInstanceKey":"\{0,1\}\([0-9]*\).*/\1/p' | head -1)
  if [ -z "$key" ]; then printf '%-17s %-44s %s\n' "$app" "$expected" "not found"; continue; fi
  state=$(printf '%s' "$inst" | sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p' | head -1)
  outcome=$(var "$key" loanOutcome)
  relayed=$(var "$key" outboxRelayed)
  written=$(api /v2/variables/search "{\"filter\":{\"processInstanceKey\":\"$key\",\"scopeKey\":\"$key\",\"name\":\"outbox\"}}" 2>/dev/null \
    | grep -o 'eventId' | wc -l | tr -d ' ')
  printf '%-17s %-44s %-10s %-12s %s/%s\n' "$app" "$expected" "${state:-?}" "${outcome:--}" "${relayed:-0}" "$written"
done < <(sed -n 's/^ *"\(demo-tr-loan-[0-9]*\)".*/\1/p' sample-data/loans.json)
echo "Each event is one loan-event-consumer instance in Operate. Sign or withdraw by hand: ./demo.sh loan sign|withdraw <id>"
