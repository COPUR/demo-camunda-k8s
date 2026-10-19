#!/usr/bin/env bash
# One command for the Camunda 8.10 TR FAST demo on a local Kubernetes (README.md):
#   preflight -> image build (inside Docker) -> image hand-off to the cluster -> credentials as Secrets
#   -> PostgreSQL + Keycloak -> Camunda 8.10 (pinned Helm chart) -> Keycloak clients -> simulators + workers
#   -> synthetic sample data -> verification.
# Safe to run again: existing Secrets are kept, everything else is applied idempotently.
#
#   DEMO_CLUSTER=auto|docker-desktop|current   which cluster (default auto, see lib/common.sh)
#   DEMO_IMAGE_LOAD="<command>"                 how to hand the image to a cluster that does not see Docker's images
#   DEMO_SKIP_TESTS=true                        build the image without running the unit tests
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh

# --- 1. Preflight ---------------------------------------------------------------------------------------------
info "preflight"
for tool in docker kubectl helm openssl curl; do
  command -v "$tool" >/dev/null || die "$tool is required (macOS: brew install kubectl helm openssl; Docker Desktop for docker)"
done
docker info >/dev/null 2>&1 || die "Docker is not running; start Docker Desktop"
helm_major=$(helm version --short | sed -E 's/^v([0-9]+)\.([0-9]+).*/\1/'); helm_minor=$(helm version --short | sed -E 's/^v([0-9]+)\.([0-9]+).*/\2/')
if [ "$helm_major" -lt 3 ] || { [ "$helm_major" -eq 3 ] && [ "$helm_minor" -lt 19 ]; }; then die "helm 3.19+ or 4.x is required"; fi
mem=$(docker info --format '{{.MemTotal}}'); cpus=$(docker info --format '{{.NCPU}}')
mem_gb=$((mem / 1024 / 1024 / 1024))
[ "$mem_gb" -ge 7 ] || die "Docker has ${mem_gb} GB of memory; give it at least 8 GB (12 GB recommended) in Settings > Resources"
[ "$mem_gb" -ge 11 ] || warn "Docker has ${mem_gb} GB of memory; 12 GB is recommended. Stop other workloads on the cluster if pods stay Pending."
[ "$cpus" -ge 6 ] || warn "Docker has ${cpus} CPUs; 6 are recommended."

select_cluster
kubectl get nodes >/dev/null 2>&1 || die "the cluster of context $DEMO_CONTEXT does not answer; enable Kubernetes in Docker Desktop"
info "cluster: context $DEMO_CONTEXT, node $(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')"
requested=$(kubectl describe nodes | sed -n '/Allocated resources/,/Events/p' | awk '$1=="memory"{gsub(/[()%]/,"",$3); print $3; exit}')
if [ -n "$requested" ] && [ "$requested" -gt 40 ] 2>/dev/null; then
  warn "other workloads already request ${requested}% of the node's memory; the demo needs about 6 GB of requests"
fi

# --- 2. Helm chart, pinned by sha256 ------------------------------------------------------------------------------
chart_url=$(sed -n 's/^url: //p' helm/chart-lock.yaml)
chart_sha=$(sed -n 's/^sha256: //p' helm/chart-lock.yaml)
mkdir -p .cache
chart=".cache/$(basename "$chart_url")"
[ -f "$chart" ] || { info "downloading the Camunda chart"; curl -sSfL -o "$chart" "$chart_url"; }
actual=$( (sha256sum "$chart" 2>/dev/null || shasum -a 256 "$chart") | cut -d' ' -f1)
[ "$actual" = "$chart_sha" ] || { rm -f "$chart"; die "chart sha256 mismatch: $actual"; }

# --- 3. Application image, built inside Docker (no host JDK) ------------------------------------------------------
info "building $IMAGE (first build downloads Gradle and dependencies, a few minutes)"
docker build -q --build-arg SKIP_TESTS="${DEMO_SKIP_TESTS:-false}" -f app/Dockerfile -t "$IMAGE" . >/dev/null
load_image

# --- 4. Namespace, credentials and TLS, all generated here and kept only in Secrets -------------------------------
kubectl apply -f k8s/00-namespace.yaml >/dev/null
secret_exists() { kubectl -n "$NS" get secret "$1" >/dev/null 2>&1; }
random() { openssl rand -hex 24; }
info "credentials (generated once per namespace, never printed)"
secret_exists demo-postgres || kubectl -n "$NS" create secret generic demo-postgres \
  --from-literal=postgres="$(random)" --from-literal=keycloak="$(random)" --from-literal=identity="$(random)" \
  --from-literal=hub="$(random)" --from-literal=camunda="$(random)" >/dev/null
secret_exists demo-keycloak || kubectl -n "$NS" create secret generic demo-keycloak --from-literal=admin-password="$(random)" >/dev/null
secret_exists demo-presenter || kubectl -n "$NS" create secret generic demo-presenter --from-literal=password="$(openssl rand -hex 12)" >/dev/null
secret_exists demo-clients || kubectl -n "$NS" create secret generic demo-clients \
  --from-literal=svc-bpo-worker-payments="$(random)" --from-literal=demo-loader="$(random)" >/dev/null
secret_exists demo-pusher || kubectl -n "$NS" create secret generic demo-pusher \
  --from-literal=app-key="$(random)" --from-literal=app-secret="$(random)" >/dev/null
secret_exists demo-oidc || kubectl -n "$NS" create secret generic demo-oidc \
  --from-literal=orchestration="$(random)" --from-literal=connectors="$(random)" >/dev/null

# A demo CA (valid 7 days) and a server certificate for Keycloak and each simulated provider. keytool runs in the
# runtime image, so the host needs no JDK.
if ! secret_exists demo-tls-ca; then
  info "demo CA and server certificates"
  work="$DEMO_TMP/tls"; mkdir -m 700 "$work"
  printf '[req]\ndistinguished_name=dn\n[dn]\n[ca]\nbasicConstraints=critical,CA:true\nkeyUsage=critical,keyCertSign,cRLSign\n' > "$work/ca.cnf"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/ca.key" 2>/dev/null
  openssl req -x509 -new -key "$work/ca.key" -subj "/CN=bpo-demo-ms local CA" -days 7 -config "$work/ca.cnf" -extensions ca -out "$work/ca.crt"
  # A truststore with an unprotected certificate entry and no MAC, so the JVMs read it without a password.
  docker run --rm --user "$(id -u):$(id -g)" -v "$work":/w --entrypoint keytool "$IMAGE" \
    -J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE -importcert -noprompt \
    -alias bpo-demo-ca -file /w/ca.crt -keystore /w/truststore.p12 -storetype PKCS12 -storepass "$(random)" >/dev/null
  kubectl -n "$NS" create secret generic demo-tls-ca --from-file=ca.crt="$work/ca.crt" --from-file=truststore.p12="$work/truststore.p12" >/dev/null
  for name in keycloak sim-psg-tr sim-acc sim-pay sim-ntf; do
    printf 'subjectAltName=DNS:%s,DNS:%s.%s.svc,DNS:%s.%s.svc.cluster.local\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\n' \
      "$name" "$name" "$NS" "$name" "$NS" > "$work/$name.ext"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/$name.key" 2>/dev/null
    openssl req -new -key "$work/$name.key" -subj "/CN=$name" -out "$work/$name.csr"
    openssl x509 -req -in "$work/$name.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" -CAcreateserial -days 7 \
      -extfile "$work/$name.ext" -out "$work/$name.crt" 2>/dev/null
    kubectl -n "$NS" create secret generic "demo-tls-$name" --from-file=tls.crt="$work/$name.crt" --from-file=tls.key="$work/$name.key" >/dev/null
  done
fi

# --- 5. Models and sample data as ConfigMaps ---------------------------------------------------------------------
kubectl -n "$NS" create configmap demo-models --from-file=models/ --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap demo-scenarios --from-file=sample-data/scenarios.json --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap demo-loader --from-file=sample-data/load.sh --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# --- 6. PostgreSQL and Keycloak ----------------------------------------------------------------------------------
info "PostgreSQL and Keycloak"
kubectl apply -f k8s/50-network-policy.yaml -f k8s/10-postgres.yaml -f k8s/20-keycloak.yaml >/dev/null
kubectl -n "$NS" rollout status statefulset/postgres --timeout=300s
kubectl -n "$NS" rollout status deployment/keycloak --timeout=600s

# --- 7. Camunda 8.10 ----------------------------------------------------------------------------------------------
info "Camunda 8.10 (orchestration cluster, connectors, Identity, Hub); several minutes the first time"
helm upgrade --install camunda "$chart" -n "$NS" -f helm/values-camunda.yaml --wait --timeout 20m >/dev/null

# --- 8. Keycloak service clients, then simulators and workers --------------------------------------------------
info "Keycloak service clients"
kubectl -n "$NS" delete job keycloak-clients --ignore-not-found >/dev/null
kubectl apply -f k8s/30-keycloak-clients.yaml >/dev/null
kubectl -n "$NS" wait --for=condition=complete job/keycloak-clients --timeout=300s >/dev/null

info "provider simulators and payments workers"
kubectl apply -f k8s/40-services.yaml >/dev/null
# A re-run with a rebuilt image must restart the pods, as the tag stays :local.
kubectl -n "$NS" rollout restart deployment/sim-psg-tr deployment/sim-acc deployment/sim-pay deployment/sim-ntf deployment/bpo-worker-payments >/dev/null
for d in sim-psg-tr sim-acc sim-pay sim-ntf bpo-worker-payments; do kubectl -n "$NS" rollout status "deployment/$d" --timeout=300s; done

# --- 9. Sample data and verification ------------------------------------------------------------------------------
info "synthetic sample data (9 TR FAST payments)"
kubectl -n "$NS" delete job demo-loader --ignore-not-found >/dev/null
kubectl apply -f k8s/60-loader-job.yaml >/dev/null
kubectl -n "$NS" wait --for=condition=complete job/demo-loader --timeout=300s >/dev/null
kubectl -n "$NS" logs job/demo-loader | tail -1

holds=""
for _ in $(seq 1 40); do
  holds=$(kubectl -n "$NS" logs deploy/sim-acc 2>/dev/null | sed -n 's/.*holdsCaptured=\([0-9]*\).*/\1/p' | tail -1)
  [ "${holds:-0}" -ge 4 ] && break
  sleep 3
done
if [ "${holds:-0}" -ge 4 ]; then info "verified: acc simulator captured $holds holds (expected 4 settled payments)"
else warn "acc simulator reports holdsCaptured=${holds:-0}, expected 4; see ./status.sh"; fi

cat <<MSG

Demo is up on context $DEMO_CONTEXT, namespace $NS.
Publish the UIs on this machine only (127.0.0.1), in another terminal:
  ./open.sh
Then sign in with username "presenter":
  Operate   http://localhost:8080/operate
  Tasklist  http://localhost:8080/tasklist
  Hub       http://localhost:8070
  Identity  http://localhost:8084
Read the generated password yourself:
  kubectl --context $DEMO_CONTEXT -n $NS get secret demo-presenter -o jsonpath='{.data.password}' | base64 -d; echo
Health: ./status.sh     Remove everything: ./uninstall.sh
MSG
