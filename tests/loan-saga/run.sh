#!/usr/bin/env bash
# End-to-end check of the consumer loan saga without a cluster: the repo's pinned Camunda 8.10 image and the
# loader's pinned curl image in Docker, the real sample-data/load.sh against models/, then the expected outcome,
# outbox and relay mark of each demo-tr-loan-* application. Takes about five minutes (the samples' timers).
# Basic auth as in the single-pod demo; the generated password stays in a private temporary directory and reaches
# the containers through env files, never a command line. The engine listens on 127.0.0.1 only.
#   tests/loan-saga/run.sh            needs docker, curl, python3, openssl
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
. "$repo/lib/common.sh"
for tool in docker curl python3 openssl; do command -v "$tool" >/dev/null || die "$tool is required"; done
pin_docker

engine=$(awk '
  /^orchestration:/ { o = 1; next }
  /^[^ #]/          { o = 0 }
  o && /^  image:/  { i = 1; next }
  o && /^  [^ ]/    { i = 0 }
  o && i && $1 == "repository:" { r = $2 }
  o && i && $1 == "tag:"        { t = $2 }
  o && i && $1 == "digest:"     { d = $2 }
  END { if (r != "" && t != "" && d != "") print r ":" t "@" d }' "$repo/helm/values-camunda.yaml")
[ -n "$engine" ] || die "no orchestration image (repository, tag, digest) in helm/values-camunda.yaml"
loader=$(sed -n 's/^ *image: \(curlimages\/curl:.*@sha256:[0-9a-f]*\)$/\1/p' "$repo/k8s/60-loader-job.yaml" | head -1)
[ -n "$loader" ] || die "no pinned curl image in k8s/60-loader-job.yaml"

umask 077
work=$(mktemp -d)
name="loan-saga-e2e-$$"
cleanup() { docker rm -f "$name" "$name-loader" >/dev/null 2>&1 || true; docker network rm "$name" >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

printf '%s' "$(openssl rand -hex 16)" > "$work/pass"
printf 'user = "presenter:%s"\n' "$(cat "$work/pass")" > "$work/auth.curl"
cat > "$work/engine.env" <<EOF
CAMUNDA_DATA_SECONDARYSTORAGE_TYPE=rdbms
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL=jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME=sa
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD=
CAMUNDA_SECURITY_AUTHENTICATION_METHOD=BASIC
CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=false
CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED=true
CAMUNDA_PROCESSINSTANCECREATION_BUSINESSIDUNIQUENESSENABLED=true
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_USERNAME=presenter
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_PASSWORD=$(cat "$work/pass")
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_NAME=Presenter
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_EMAIL=presenter@example.invalid
CAMUNDA_SECURITY_INITIALIZATION_DEFAULTROLES_ADMIN_USERS_0=presenter
EOF
printf 'CRED=%s\n' "$(cat "$work/pass")" > "$work/loader.env"

docker network create "$name" >/dev/null
info "starting $engine on 127.0.0.1"
docker run -d --name "$name" --network "$name" --network-alias camunda --env-file "$work/engine.env" \
  -p 127.0.0.1::8080 --memory 3g "$engine" >/dev/null
port=$(docker port "$name" 8080/tcp | sed -n 's/^127\.0\.0\.1:\([0-9][0-9]*\)$/\1/p' | head -n 1)
[ -n "$port" ] || die "no port published for the engine"
rest="http://127.0.0.1:$port"
tries=0
until curl -sf --noproxy '*' -K "$work/auth.curl" "$rest/v2/topology" 2>/dev/null | grep -q '"role":"leader"'; do
  tries=$((tries + 1))
  [ "$tries" -lt 120 ] || { docker logs --tail 40 "$name" >&2; die "the engine did not become ready"; }
  sleep 2
done

info "running sample-data/load.sh in $loader"
docker run --name "$name-loader" --network "$name" --env-file "$work/loader.env" --user 100 --read-only \
  --tmpfs /tmp -v "$repo/sample-data:/scripts:ro" -v "$repo/models:/models:ro" -v "$repo/sample-data:/data:ro" \
  "$loader" sh /scripts/load.sh > "$work/loader.log" 2>&1 || { cat "$work/loader.log" >&2; die "the loader failed"; }
grep -q 'Msg_LoanContractSigned for demo-tr-loan-001 already published' "$work/loader.log" \
  || { cat "$work/loader.log" >&2; die "the repeated signature was not dropped by its messageId"; }

info "waiting for the samples' timers (signature window 1 min, withdrawal period 3 min)"
REST="$rest" AUTH="$work/auth.curl" python3 -I - <<'PY'
import base64, json, os, re, sys, time, urllib.request

rest = os.environ["REST"]
user, password = re.match(r'user = "([^:]*):(.*)"', open(os.environ["AUTH"]).read()).groups()
auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def post(path, body):
    req = urllib.request.Request(rest + path, data=json.dumps(body).encode(),
                                 headers={"content-type": "application/json", "authorization": auth})
    return json.load(opener.open(req, timeout=10))

def instance(app):
    items = post("/v2/process-instances/search",
                 {"filter": {"processDefinitionId": "loan-personal-saga", "businessId": app}})["items"]
    return items[0] if items else None

def root_vars(key):
    items = post("/v2/variables/search",
                 {"filter": {"processInstanceKey": key, "scopeKey": key}, "page": {"limit": 100}})["items"]
    return {v["name"]: json.loads(v["value"]) for v in items}

forward = ["LimitReserved", "ContractDrawnUp"]
rolled_back = forward + ["ContractCancelled", "LimitReleased", "LoanSagaCompensated", "ApplicationRolledBack"]
expected = {  # outcome, outbox in order (a set where the engine runs the handlers at once), final state
    "demo-tr-loan-001": ("ACTIVE", forward + ["LoanDisbursed", "LoanActivated"], "COMPLETED"),
    "demo-tr-loan-002": ("DECLINED", ["ApplicationDeclined"], "COMPLETED"),
    "demo-tr-loan-004": ("ROLLED_BACK", rolled_back, "COMPLETED"),
    "demo-tr-loan-005": ("ROLLED_BACK", rolled_back, "COMPLETED"),
    "demo-tr-loan-006": ("WITHDRAWN", forward + ["LoanDisbursed",
                         {"LimitReleased", "ContractCancelled", "DisbursementCollectedBack"}, "LoanWithdrawn"], "COMPLETED"),
}

def matches(types, pattern):
    i = 0
    for p in pattern:
        if isinstance(p, set):
            if set(types[i:i + len(p)]) != p:
                return False
            i += len(p)
        else:
            if i >= len(types) or types[i] != p:
                return False
            i += 1
    return i == len(types)

deadline = time.time() + 420
while True:
    pending = [a for a in expected if (instance(a) or {}).get("state") != "COMPLETED"]
    if not pending or time.time() > deadline:
        break
    time.sleep(5)
time.sleep(3)  # let the exporter catch up with the last variables

failures, relayed_total = [], 0
for app, (outcome, pattern, state) in expected.items():
    inst = instance(app)
    if not inst:
        failures.append(f"{app}: no instance")
        continue
    v = root_vars(inst["processInstanceKey"])
    types = [e["type"] for e in v.get("outbox", [])]
    ids = [e["eventId"] for e in v.get("outbox", [])]
    relayed_total += v.get("outboxRelayed", 0)
    problems = []
    if inst["state"] != state: problems.append(f"state {inst['state']}")
    if inst.get("hasIncident"): problems.append("incident")
    if v.get("loanOutcome") != outcome: problems.append(f"outcome {v.get('loanOutcome')}")
    if not matches(types, pattern): problems.append(f"outbox {types}")
    if v.get("outboxRelayed") != len(types): problems.append(f"relayed {v.get('outboxRelayed')} of {len(types)}")
    if len(set(ids)) != len(ids): problems.append("duplicate event ids")
    print(f"{app}  {inst['state']:<9} {str(v.get('loanOutcome')):<11} relayed {v.get('outboxRelayed')}/{len(types)}  "
          + " > ".join(types))
    if problems: failures.append(f"{app}: " + ", ".join(problems))

inst = instance("demo-tr-loan-003")
tasks = post("/v2/user-tasks/search", {"filter": {"elementId": "Task_UnderwriterReview", "state": "CREATED"}})["items"]
if not inst or inst["state"] != "ACTIVE" or not any(t["processInstanceKey"] == inst["processInstanceKey"] for t in tasks):
    failures.append("demo-tr-loan-003: no open underwriter task")
else:
    print("demo-tr-loan-003  ACTIVE    waits for the underwriter (candidate group loan-underwriters)")

consumed = post("/v2/process-instances/search",
                {"filter": {"processDefinitionId": "loan-event-consumer"}, "page": {"limit": 1}})["page"]["totalItems"]
print(f"loan-event-consumer instances: {consumed} (events relayed: {relayed_total})")
if consumed != relayed_total:
    failures.append(f"{consumed} consumer instances for {relayed_total} relayed events")

if failures:
    print("FAILED:\n  " + "\n  ".join(failures), file=sys.stderr)
    sys.exit(1)
print("loan saga: all expectations met")
PY
