#!/usr/bin/env bash
# Runs RealEngineE2eTest (the tests tagged e2e) against the repo's pinned Camunda 8.10 image in Docker, set up the
# way helm/values-camunda.yaml sets up the demo's engine: authorizations on, business id uniqueness on, and the
# API's engine identity holding only the four P2 permissions. Two differences, both for a self-contained run: basic
# auth instead of Keycloak (so the grant's owner is a USER here and a CLIENT in the cluster) and secondary storage
# in an in-memory H2, so nothing outlives the run.
# The engine listens on 127.0.0.1 only. Its passwords are generated for this run, stay in a private temporary
# directory and reach the container through an env file, never a command line.
#   app/payment-exception-api/e2e/run.sh [extra Gradle arguments, e.g. --tests '*Escalated*']
# E2E_GRADLE replaces the Gradle wrapper (a wrapper script with a mirror or a JDK of your own).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
. "$repo/lib/common.sh"
for tool in docker openssl curl; do command -v "$tool" >/dev/null || die "$tool is required"; done
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
name="pex-e2e-$$"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

printf '%s' "$(openssl rand -hex 16)" > "$work/admin.pass"
printf '%s' "$(openssl rand -hex 16)" > "$work/api.pass"
printf 'user = "e2e-admin:%s"\n' "$(cat "$work/admin.pass")" > "$work/admin.curl"
cat > "$work/camunda.env" <<EOF
CAMUNDA_DATA_SECONDARYSTORAGE_TYPE=rdbms
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL=jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME=sa
CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD=
CAMUNDA_SECURITY_AUTHENTICATION_METHOD=BASIC
CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=false
CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED=true
CAMUNDA_PROCESSINSTANCECREATION_BUSINESSIDUNIQUENESSENABLED=true
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_USERNAME=e2e-admin
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_PASSWORD=$(cat "$work/admin.pass")
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_NAME=E2E Admin
CAMUNDA_SECURITY_INITIALIZATION_USERS_0_EMAIL=e2e-admin@example.invalid
CAMUNDA_SECURITY_INITIALIZATION_USERS_1_USERNAME=svc-payment-exception-api
CAMUNDA_SECURITY_INITIALIZATION_USERS_1_PASSWORD=$(cat "$work/api.pass")
CAMUNDA_SECURITY_INITIALIZATION_USERS_1_NAME=Payment exception API
CAMUNDA_SECURITY_INITIALIZATION_USERS_1_EMAIL=svc-payment-exception-api@example.invalid
CAMUNDA_SECURITY_INITIALIZATION_DEFAULTROLES_ADMIN_USERS_0=e2e-admin
CAMUNDA_SECURITY_INITIALIZATION_AUTHORIZATIONS_0_OWNERTYPE=USER
CAMUNDA_SECURITY_INITIALIZATION_AUTHORIZATIONS_0_OWNERID=svc-payment-exception-api
CAMUNDA_SECURITY_INITIALIZATION_AUTHORIZATIONS_0_RESOURCETYPE=PROCESS_DEFINITION
CAMUNDA_SECURITY_INITIALIZATION_AUTHORIZATIONS_0_RESOURCEID=pay-instant-exception
CAMUNDA_SECURITY_INITIALIZATION_AUTHORIZATIONS_0_PERMISSIONS_0=CREATE_PROCESS_INSTANCE
CAMUNDA_SECURITY_INITIALIZATION_AUTHORIZATIONS_0_PERMISSIONS_1=READ_PROCESS_INSTANCE
CAMUNDA_SECURITY_INITIALIZATION_AUTHORIZATIONS_0_PERMISSIONS_2=READ_USER_TASK
CAMUNDA_SECURITY_INITIALIZATION_AUTHORIZATIONS_0_PERMISSIONS_3=COMPLETE_USER_TASK
EOF

info "starting $image on 127.0.0.1"
docker run -d --name "$name" --env-file "$work/camunda.env" -p 127.0.0.1::8080 --memory 3g "$image" >/dev/null
port=$(docker port "$name" 8080/tcp | sed -n 's/^127\.0\.0\.1:\([0-9][0-9]*\)$/\1/p' | head -n 1)
[ -n "$port" ] || die "no port published for the engine"
rest="http://127.0.0.1:$port"

# Ready when the one partition has a leader (at most four minutes).
tries=0
until curl -sf -K "$work/admin.curl" "$rest/v2/topology" 2>/dev/null | grep -q '"role":"leader"'; do
  tries=$((tries + 1))
  if [ "$tries" -ge 120 ] || [ "$(docker inspect -f '{{.State.Running}}' "$name")" != true ]; then
    docker logs --tail 40 "$name" >&2 || true
    die "the engine did not become ready"
  fi
  sleep 2
done
info "engine ready at $rest; running the e2e tests"

cd "$repo/app"
${E2E_GRADLE:-./gradlew} --no-daemon --console=plain :payment-exception-api:e2eTest \
  "-De2e.camunda.rest-address=$rest" \
  "-De2e.camunda.admin-password-file=$work/admin.pass" \
  "-De2e.camunda.api-password-file=$work/api.pass" \
  "-De2e.models=$repo/models" "$@"
