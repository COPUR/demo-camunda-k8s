#!/usr/bin/env bash
# Runs status.sh against a kubectl and curl test double: no cluster, no network. Each case writes the Camunda
# search responses the double serves, then checks the exit code and the CHECK column.
#   bash tests/status_test.sh
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"

# kubectl: a local docker-desktop context, empty pods and logs, a port-forward that just waits, a dummy secret.
cat > "$work/bin/kubectl" <<'SH'
#!/usr/bin/env bash
case "$*" in
  *"config get-contexts"*) echo docker-desktop ;;
  *"config view --minify"*) echo "apiVersion: v1" ;;
  *"config view -o jsonpath"*) printf 'https://127.0.0.1:6443' ;;
  *" get pods"*) echo "NAME READY STATUS" ;;
  *" port-forward "*) exec sleep 30 ;;
  *" get secret "*) printf 'dGVzdA==' ;;
  *) : ;;
esac
SH
# curl: serves $FIXTURES/variables.json or instances.json by the request path; fails when the file is absent.
cat > "$work/bin/curl" <<'SH'
#!/usr/bin/env bash
cat > /dev/null
for a in "$@"; do
  case "$a" in
    */v2/variables/search) f=variables.json ;;
    */v2/process-instances/search) f=instances.json ;;
  esac
done
[ -f "$FIXTURES/${f:-none}" ] || exit 7
cat "$FIXTURES/$f"
SH
chmod +x "$work/bin/kubectl" "$work/bin/curl"

ids=$(sed -n 's/^ *"\(demo-[a-z0-9-]*\)".*/\1/p' "$root/sample-data/scenarios.json")

# fixtures DIR [SKIP_ID] [WRONG_ID]: one paymentId variable and one instance per payment, in the shape Camunda's
# v2 search returns (keys as strings, the variable value JSON-encoded). SKIP_ID has no instance at all; WRONG_ID
# ends in the opposite state.
fixtures() {
  local dir=$1 skip=${2:-} wrong=${3:-} n=0 id state v="" i=""
  mkdir -p "$dir"
  for id in $ids; do
    n=$((n + 1))
    [ "$id" = "$skip" ] && continue
    case "$id" in demo-tr-unres-*) state=ACTIVE ;; *) state=COMPLETED ;; esac
    if [ "$id" = "$wrong" ]; then
      if [ "$state" = ACTIVE ]; then state=COMPLETED; else state=ACTIVE; fi
    fi
    v+="${v:+,}{\"variableKey\":\"9$n\",\"name\":\"paymentId\",\"value\":\"\\\"$id\\\"\",\"processInstanceKey\":\"225179981368$n\",\"truncated\":false}"
    i+="${i:+,}{\"processInstanceKey\":\"225179981368$n\",\"processDefinitionId\":\"pay-instant-exception\",\"state\":\"$state\",\"hasIncident\":false}"
  done
  printf '{"items":[%s],"page":{"totalItems":%d}}' "$v" "$n" > "$dir/variables.json"
  printf '{"items":[%s],"page":{"totalItems":%d}}' "$i" "$n" > "$dir/instances.json"
}

fails=0
# check NAME DIR WANT_EXIT [PATTERN...]: runs status.sh, compares the exit code, greps the output for each pattern.
check() {
  local name=$1 dir=$2 want=$3 got=0 out p
  shift 3
  out=$(FIXTURES="$dir" PATH="$work/bin:$PATH" DEMO_STATUS_PORT=18199 DEMO_STATUS_TRIES=2 "$root/status.sh" 2>&1) || got=$?
  if [ "$got" != "$want" ]; then
    echo "FAIL $name: exit $got, want $want"; printf '%s\n' "$out"; fails=$((fails + 1)); return
  fi
  for p in "$@"; do
    if ! grep -Eq -- "$p" <<<"$out"; then
      echo "FAIL $name: no line matching /$p/"; printf '%s\n' "$out"; fails=$((fails + 1)); return
    fi
  done
  echo "ok   $name"
}

fixtures "$work/all"
check "all as expected" "$work/all" 0 \
  'demo-tr-acsc-001 +settled \(COMPLETED\) +COMPLETED +OK' \
  'demo-tr-pdng-001 +settled \(COMPLETED\) +COMPLETED +OK' \
  'demo-tr-rjct-002 +rejected \(COMPLETED\) +COMPLETED +OK' \
  'demo-tr-unres-003 +waits for an ops decision \(ACTIVE\) +ACTIVE +OK' \
  'all 9 payments as expected'

fixtures "$work/missing" demo-tr-unres-002
check "one missing" "$work/missing" 1 \
  'demo-tr-unres-002 +waits for an ops decision \(ACTIVE\) +not found +MISSING' \
  'demo-tr-unres-001 .* OK$' \
  '1 of 9 payments not as expected'

fixtures "$work/wrong" "" demo-tr-acsc-002
check "one in the wrong state" "$work/wrong" 1 \
  'demo-tr-acsc-002 +settled \(COMPLETED\) +ACTIVE +WRONG' \
  '1 of 9 payments not as expected'

mkdir -p "$work/down"
check "Camunda does not answer" "$work/down" 2 'payments could not be checked'

[ "$fails" -eq 0 ] || { echo "$fails case(s) failed"; exit 1; }
echo "all cases passed"
