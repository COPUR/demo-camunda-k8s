#!/usr/bin/env bash
# Checks what the presenter sees in Tasklist and Operate against the repo's pinned Camunda 8.10 image in Docker,
# without a cluster: the models and sample-data/load.sh exactly as the demo-loader Job runs them, and this script in
# place of the workers (it answers the scheme status reads and wakes the waiting instances).
#   1. Static: the forms ask for enums only (no free-text field), and their keys are known case variables.
#   2. load.sh starts the nine sample payments with businessId and scenario tags; run a second time it starts none,
#      so Operate's Business ID filter finds exactly one instance for demo-tr-unres-001.
#   3. demo-tr-unres-001 (four PDNG reads): the operations task carries exceptionCode EXC-001, the last status, the
#      read count, the routing and a due date; ESCALATE with a reason reaches the supervisor with EXC-003; SETTLED
#      with a reason reaches pay.record-exception-decision.
#   4. demo-tr-unres-002: completed with exceptionDecision alone (as the exception API does), no incident.
#   5. An unclear final status (RCVD) reaches the operations task as EXC-002.
#   6. PII: every variable of every instance has a known name and an id- or enum-shaped value.
# Like app/payment-exception-api/e2e/run.sh: basic auth, H2 secondary storage, business id uniqueness on (as
# helm/values-camunda.yaml sets it once the exception API is in), the engine on 127.0.0.1 only, and generated
# passwords kept in a private temporary directory, never on a command line.
#   models/e2e/run.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
. "$repo/lib/common.sh"
for tool in docker openssl curl jq; do command -v "$tool" >/dev/null || die "$tool is required"; done

fail() { die "FAIL: $*"; }
pass() { printf '\033[1;32mok\033[0m %s\n' "$*"; }

# Case variables the models and forms may write. Anything else in an instance fails the PII check.
known_vars=" paymentId jurisdiction rail sourceRef schemeStatus statusPolicy statusSignal exceptionRouting exceptionCode
  lastSchemeStatus schemeStatusReads exceptionDecision decisionReason escalationCode escalationReason exceptionOutcome
  purpose "
known_vars=$(printf '%s' "$known_vars" | tr -s ' \n' '  ')

info "static checks of the forms"
for form in "$repo"/models/*.form; do
  free=$(jq -r '[.components[] | select(.type == "textfield" or .type == "textarea" or .type == "number"
    or .type == "datetime" or .type == "filepicker") | .key] | join(" ")' "$form")
  [ -z "$free" ] || fail "$(basename "$form") has free-text inputs: $free"
  for key in $(jq -r '.components[] | select(.key != null) | .key' "$form"); do
    case "$known_vars" in *" $key "*) ;; *) fail "$(basename "$form") writes an unknown variable $key" ;; esac
  done
  bad=$(jq -r '[.components[] | select(.values != null) | .values[].value | select(test("^[A-Z][A-Z_]*$") | not)] | join(" ")' "$form")
  [ -z "$bad" ] || fail "$(basename "$form") has non-enum option values: $bad"
done
pass "forms take enums only and write known variables"

pin_docker
image=$(awk '
  /^orchestration:/ { o = 1; next }
  /^[^ #]/          { o = 0 }
  o && /^  image:/  { i = 1; next }
  o && /^  [^ ]/    { i = 0 }
  o && i && $1 == "repository:" { r = $2 }
  o && i && $1 == "tag:"        { t = $2 }
  o && i && $1 == "digest:"     { d = $2 }
  END { if (r != "" && t != "" && d != "") print r ":" t "@" d }' "$repo/helm/values-camunda.yaml")
[ -n "$image" ] || die "no orchestration image (repository, tag, digest) in helm/values-camunda.yaml"

umask 077
work=$(mktemp -d)
name="models-e2e-$$"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

pass_presenter=$(openssl rand -hex 16)
printf 'user = "presenter:%s"\n' "$pass_presenter" > "$work/auth.curl"
cat > "$work/camunda.env" <<EOF
CAMUNDA_DATA_SECONDARYSTORAGE_TYPE=rdbms
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL=jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME=sa
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD=
CAMUNDA_SECURITY_AUTHENTICATION_METHOD=BASIC
CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=false
CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED=true
CAMUNDA_PROCESSINSTANCECREATION_BUSINESSIDUNIQUENESSENABLED=true
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_USERNAME=presenter
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_PASSWORD=$pass_presenter
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_NAME=Presenter
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_EMAIL=presenter@example.invalid
CAMUNDA_SECURITY_INITIALIZATION_DEFAULTROLES_ADMIN_USERS_0=presenter
EOF

info "starting $image on 127.0.0.1"
docker run -d --name "$name" --env-file "$work/camunda.env" -p 127.0.0.1::8080 --memory 3g "$image" >/dev/null
port=$(docker port "$name" 8080/tcp | sed -n 's/^127\.0\.0\.1:\([0-9][0-9]*\)$/\1/p' | head -n 1)
[ -n "$port" ] || die "no port published for the engine"
rest="http://127.0.0.1:$port"
tries=0
until curl -sf --noproxy '*' -K "$work/auth.curl" "$rest/v2/topology" 2>/dev/null | grep -q '"role":"leader"'; do
  tries=$((tries + 1))
  if [ "$tries" -ge 120 ] || [ "$(docker inspect -f '{{.State.Running}}' "$name")" != true ]; then
    docker logs --tail 40 "$name" >&2 || true
    die "the engine did not become ready"
  fi
  sleep 2
done

# api METHOD PATH [JSON]: one REST call as the presenter; prints the body, fails on an HTTP error.
api() {
  curl -sS --noproxy '*' --fail-with-body -K "$work/auth.curl" -H 'content-type: application/json' -X "$1" "$rest$2" \
    ${3:+-d "$3"}
}
# The search side follows the engine's log, so the reads below retry for up to a minute.
instance_of() { # instance_of PAYMENT-ID: the instance key, from the business id
  api POST /v2/process-instances/search "{\"filter\":{\"processDefinitionId\":\"pay-instant-exception\",\"businessId\":\"$1\"}}" \
    | jq -r '.items[0].processInstanceKey // empty'
}
vars_of() { # vars_of INSTANCE-KEY: the instance's process-scope variables as one JSON object
  api POST /v2/variables/search "{\"filter\":{\"processInstanceKey\":\"$1\",\"scopeKey\":\"$1\"},\"page\":{\"limit\":100}}" \
    | jq -c '[.items[] | {key: .name, value: (.value | fromjson)}] | from_entries'
}
open_task() { # open_task INSTANCE-KEY ELEMENT-ID: the created task, as JSON
  api POST /v2/user-tasks/search "{\"filter\":{\"processInstanceKey\":\"$1\",\"elementId\":\"$2\",\"state\":\"CREATED\"}}" \
    | jq -ce '.items[0] // empty'
}
# answer_reads STATUS PAYMENT-ID...: completes each open psg.status-inquiry job of these payments with STATUS, then
# wakes them with Msg_SchemeStatusReported as load.sh does. Other jobs are left alone.
answer_reads() {
  local status=$1 jobs; shift
  jobs=$(api POST /v2/jobs/activation '{"type":"psg.status-inquiry","worker":"models-e2e","timeout":60000,"maxJobsToActivate":50,"requestTimeout":1000,"fetchVariable":["sourceRef"]}')
  printf '%s' "$jobs" | jq -r '.jobs[] | "\(.jobKey) \(.variables.sourceRef)"' | while read -r key ref; do
    case " $* " in
      *" $ref "*) api POST "/v2/jobs/$key/completion" "{\"variables\":{\"schemeStatus\":\"$status\"}}" >/dev/null ;;
      *) api POST "/v2/jobs/$key/failure" '{"retries":5,"retryBackOff":0,"errorMessage":"not this test'"'"'s payment"}' >/dev/null ;;
    esac
  done
  for ref in "$@"; do
    api POST /v2/messages/publication "{\"name\":\"Msg_SchemeStatusReported\",\"correlationKey\":\"$ref\",\"timeToLive\":0}" >/dev/null
  done
}
expect_eq() { [ "$2" = "$3" ] || fail "$1: expected '$3', got '$2'"; }

loader() {
  mkdir -p "$work/loader"
  BPO_DEMO_BASE=$rest BPO_DEMO_MODELS="$repo/models" BPO_DEMO_DATA="$repo/sample-data" BPO_DEMO_TMP="$work/loader" \
    BPO_DEMO_WAKE_ROUNDS=0 CRED=$pass_presenter no_proxy='*' NO_PROXY='*' sh "$repo/sample-data/load.sh"
}

info "load.sh, first run"
loader > "$work/load1.log" || { cat "$work/load1.log" >&2; fail "load.sh failed"; }
expect_eq "payments started" "$(grep -c '^started P2' "$work/load1.log")" 9
pass "load.sh started 9 payments"

unres1=""
for _ in $(seq 60); do unres1=$(instance_of demo-tr-unres-001); [ -n "$unres1" ] && break; sleep 1; done
[ -n "$unres1" ] || fail "no instance with business id demo-tr-unres-001"
# The engine hands the business id on to the called status library, so Operate's Business ID filter shows the case
# and its Await external status instance; filtered to the P2 process it is one.
inst=$(api POST /v2/process-instances/search '{"filter":{"businessId":"demo-tr-unres-001"}}')
expect_eq "P2 instances for demo-tr-unres-001" "$(printf '%s' "$inst" | jq '[.items[] | select(.processDefinitionId == "pay-instant-exception")] | length')" 1
expect_eq "other instances are the case's called library" \
  "$(printf '%s' "$inst" | jq -r --arg k "$unres1" '[.items[] | select(.processDefinitionId != "pay-instant-exception") | "\(.processDefinitionId) \(.parentProcessInstanceKey == $k)"] | unique | join(",")')" \
  "lib-await-external-status true"
expect_eq "tags" "$(printf '%s' "$inst" | jq -r '.items[] | select(.processDefinitionId == "pay-instant-exception") | .tags | sort | join(",")')" "demo,jurisdiction:TR,rail:FAST,scenario:unres"
pass "demo-tr-unres-001: one P2 instance with business id and tags (plus its status library)"

info "load.sh, second run"
loader > "$work/load2.log" || { cat "$work/load2.log" >&2; fail "load.sh failed on the second run"; }
expect_eq "payments started again" "$(grep -c '^started P2' "$work/load2.log" || true)" 0
expect_eq "payments skipped" "$(grep -c 'skipped$' "$work/load2.log")" 9
expect_eq "P2 instances for demo-tr-unres-001 after the second run" \
  "$(api POST /v2/process-instances/search '{"filter":{"processDefinitionId":"pay-instant-exception","businessId":"demo-tr-unres-001"}}' | jq '.items | length')" 1
pass "second run started nothing; the Business ID filter still finds one instance"

info "demo-tr-unres-001 and -002: four PDNG reads"
for _ in 1 2 3 4; do
  sleep 2
  answer_reads PDNG demo-tr-unres-001 demo-tr-unres-002
done
unres2=$(instance_of demo-tr-unres-002)
task=""
for _ in $(seq 60); do task=$(open_task "$unres1" Task_OpsReview) && [ -n "$task" ] && break; answer_reads PDNG demo-tr-unres-001 demo-tr-unres-002; sleep 1; done
[ -n "$task" ] || fail "no operations task for demo-tr-unres-001"
expect_eq "operations queue" "$(printf '%s' "$task" | jq -r '.candidateGroups | join(",")')" pay-tr-fast-ops
[ -n "$(printf '%s' "$task" | jq -r '.dueDate // empty')" ] || fail "the operations task has no due date"
vars=$(vars_of "$unres1")
expect_eq exceptionCode "$(printf '%s' "$vars" | jq -r .exceptionCode)" EXC-001
expect_eq lastSchemeStatus "$(printf '%s' "$vars" | jq -r .lastSchemeStatus)" PDNG
expect_eq schemeStatusReads "$(printf '%s' "$vars" | jq -r .schemeStatusReads)" 4
expect_eq "routing" "$(printf '%s' "$vars" | jq -r .exceptionRouting.opsSla)" PT30M
form=$(api GET "/v2/user-tasks/$(printf '%s' "$task" | jq -r .userTaskKey)/form" | jq -r .schema)
printf '%s' "$form" | grep -q 'Runbook' || fail "the operations task's form is not the new one"
printf '%s' "$form" | grep -q 'operations view' && fail "the form still points at the payments operations view"
pass "operations task: EXC-001, PDNG after 4 reads, routing, due date, new form"

info "ESCALATE with a reason, then SETTLED by the supervisor"
api POST "/v2/user-tasks/$(printf '%s' "$task" | jq -r .userTaskKey)/completion" \
  '{"variables":{"exceptionDecision":"ESCALATE","escalationReason":"SCHEME_RECORD_CONFLICT"}}' >/dev/null
sup=""
for _ in $(seq 60); do sup=$(open_task "$unres1" Task_SupervisorReview) && [ -n "$sup" ] && break; sleep 1; done
[ -n "$sup" ] || fail "no supervisor task for demo-tr-unres-001"
expect_eq "supervisor queue" "$(printf '%s' "$sup" | jq -r '.candidateGroups | join(",")')" pay-tr-fast-supervisor
[ -n "$(printf '%s' "$sup" | jq -r '.dueDate // empty')" ] || fail "the supervisor task has no due date"
vars=$(vars_of "$unres1")
expect_eq escalationCode "$(printf '%s' "$vars" | jq -r .escalationCode)" EXC-003
expect_eq escalationReason "$(printf '%s' "$vars" | jq -r .escalationReason)" SCHEME_RECORD_CONFLICT
api POST "/v2/user-tasks/$(printf '%s' "$sup" | jq -r .userTaskKey)/completion" \
  '{"variables":{"exceptionDecision":"SETTLED","decisionReason":"SCHEME_RECORD_FINAL"}}' >/dev/null
recorded=""
for _ in $(seq 30); do
  recorded=$(api POST /v2/jobs/activation '{"type":"pay.record-exception-decision","worker":"models-e2e","timeout":600000,"maxJobsToActivate":10,"requestTimeout":1000}' \
    | jq -r --arg k "$unres1" '.jobs[] | select(.processInstanceKey == $k) | .variables | "\(.exceptionDecision) \(.decisionReason)"')
  [ -n "$recorded" ] && break; sleep 1
done
expect_eq "decision handed to pay.record-exception-decision" "$recorded" "SETTLED SCHEME_RECORD_FINAL"
pass "supervisor task: EXC-003 with its reason; the decision and its reason reach pay's worker"

info "demo-tr-unres-002: decision without a reason (the exception API's call)"
task2=""
for _ in $(seq 60); do task2=$(open_task "$unres2" Task_OpsReview) && [ -n "$task2" ] && break; sleep 1; done
[ -n "$task2" ] || fail "no operations task for demo-tr-unres-002"
api POST "/v2/user-tasks/$(printf '%s' "$task2" | jq -r .userTaskKey)/completion" '{"variables":{"exceptionDecision":"REJECTED"}}' >/dev/null
recorded=""
for _ in $(seq 30); do
  recorded=$(api POST /v2/jobs/activation '{"type":"pay.record-exception-decision","worker":"models-e2e","timeout":600000,"maxJobsToActivate":10,"requestTimeout":1000}' \
    | jq -r --arg k "$unres2" '.jobs[] | select(.processInstanceKey == $k) | .variables.exceptionDecision')
  [ -n "$recorded" ] && break; sleep 1
done
expect_eq "decision without a reason" "$recorded" REJECTED
incidents=$(api POST /v2/incidents/search '{"filter":{"state":"ACTIVE"}}' | jq '.items | length')
expect_eq "active incidents" "$incidents" 0
pass "a decision without a reason goes through without an incident"

info "an unclear final status (RCVD)"
api POST /v2/process-instances '{"processDefinitionId":"pay-instant-exception","businessId":"demo-tr-e2e-unclear","variables":{"paymentId":"demo-tr-e2e-unclear","jurisdiction":"TR","rail":"FAST"}}' >/dev/null
unclear=""
for _ in $(seq 60); do
  answer_reads RCVD demo-tr-e2e-unclear
  unclear=$(instance_of demo-tr-e2e-unclear)
  [ -n "$unclear" ] && [ -n "$(open_task "$unclear" Task_OpsReview)" ] && break
  sleep 1
done
vars=$(vars_of "$unclear")
expect_eq "unclear exceptionCode" "$(printf '%s' "$vars" | jq -r .exceptionCode)" EXC-002
expect_eq "unclear lastSchemeStatus" "$(printf '%s' "$vars" | jq -r .lastSchemeStatus)" RCVD
pass "an unclear status reaches operations as EXC-002"

info "PII: every variable of every instance"
all=$(api POST /v2/variables/search '{"page":{"limit":1000}}')
for n in $(printf '%s' "$all" | jq -r '.items[].name' | sort -u); do
  case "$known_vars" in *" $n "*) ;; *) fail "unknown variable $n" ;; esac
done
# Values are ids, enums (letters and _ only, so an IBAN does not pass), EXC codes, ISO durations, queue names, small
# numbers, null, or the routing object made of those.
bad=$(printf '%s' "$all" | jq -r '.items[] | .value | fromjson | [.. | scalars] | .[] | tostring
  | select(test("^(demo-[a-z0-9-]+|[A-Z][A-Z_]*|EXC-[0-9]{3}|PT[0-9]+[SMH]|[a-z]+(-[a-z0-9]+)+|[0-9]{1,3}|null|true|false)$") | not)')
[ -z "$bad" ] || fail "variable values that are not ids or enums: $(printf '%s' "$bad" | head -5 | tr '\n' ' ')"
for id in $(printf '%s' "$all" | jq -r '.items[] | select(.name == "paymentId" or .name == "sourceRef") | .value | fromjson'); do
  case "$id" in demo-tr-*) ;; *) fail "a payment id that is not synthetic: $id" ;; esac
done
pass "$(printf '%s' "$all" | jq '.items | length') variables, all known names with id or enum values"

info "all checks passed"
