# Sourced by install.sh, open.sh, status.sh and uninstall.sh.
# 1. Picks the cluster and refuses any whose API server is not on this machine (throwaway local clusters only).
# 2. Pins every later kubectl and helm call to that one cluster through a private, minified kubeconfig, so a
#    context switch in another terminal mid-run cannot redirect them. Your own current context is left as it is.
# 3. Knows how to hand the locally built image to the cluster's container runtime.

# shellcheck disable=SC2034  # NS and IMAGE are read by the scripts that source this file
NS=bpo-demo-ms
IMAGE=bpo-payments-demo:local

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# DEMO_CLUSTER: auto (default) = context docker-desktop when it exists, else the current context;
#               docker-desktop = Docker Desktop's built-in Kubernetes; current = the current kubectl context.
select_cluster() {
  local mode="${DEMO_CLUSTER:-auto}" context
  case "$mode" in
    auto)
      if kubectl config get-contexts -o name 2>/dev/null | grep -qx docker-desktop; then context=docker-desktop
      else context=$(kubectl config current-context 2>/dev/null) || die "no kubectl context; enable Kubernetes in Docker Desktop"; fi ;;
    docker-desktop) context=docker-desktop ;;
    current) context=$(kubectl config current-context) ;;
    *) die "DEMO_CLUSTER must be auto, docker-desktop or current" ;;
  esac
  DEMO_CONTEXT="$context"
  DEMO_TMP=$(mktemp -d)
  trap 'rm -rf "$DEMO_TMP"' EXIT
  kubectl config view --minify --flatten --context "$context" > "$DEMO_TMP/kubeconfig" 2>/dev/null \
    || die "kubectl context $context not found"
  chmod 600 "$DEMO_TMP/kubeconfig"
  export KUBECONFIG="$DEMO_TMP/kubeconfig"
  local server
  server=$(kubectl config view -o jsonpath='{.clusters[0].cluster.server}')
  if ! [[ "$server" =~ ^https://(127\.0\.0\.1|localhost|\[::1\]|kubernetes\.docker\.internal):[0-9]{1,5}/?$ ]]; then
    die "refusing: the API server of context $context ($server) is not on this machine"
  fi
}

# Pins every later docker call to the Docker engine chosen now, as select_cluster does for kubectl: switching the
# Docker context in another terminal mid-run cannot redirect the build or the image hand-off. A remote engine
# (tcp://, ssh://) is refused, so the image and the generated certificates stay on this machine.
pin_docker() {
  local ctx endpoint
  if [ -n "${DOCKER_HOST:-}" ]; then endpoint=$DOCKER_HOST; ctx="DOCKER_HOST"
  else
    ctx="${DOCKER_CONTEXT:-$(docker context show 2>/dev/null || echo default)}"
    endpoint=$(docker context inspect "$ctx" --format '{{.Endpoints.docker.Host}}' 2>/dev/null) \
      || die "docker context $ctx not found"
    export DOCKER_CONTEXT="$ctx"
  fi
  case "$endpoint" in
    unix://*|npipe://*) ;;
    *) die "refusing: the Docker engine of $ctx ($endpoint) is not on this machine" ;;
  esac
}

# Hands $IMAGE to the cluster. DEMO_IMAGE_LOAD (a command reading an image archive on stdin) wins; otherwise:
#   a node that is itself a Docker container (Docker Desktop in kind mode, kind)  -> ctr import inside the node
#   minikube                                                                       -> minikube image load
#   anything else (Docker Desktop kubeadm mode, OrbStack, Rancher Desktop/moby)    -> the runtime already sees it
load_image() {
  if [ -n "${DEMO_IMAGE_LOAD:-}" ]; then
    info "loading $IMAGE with DEMO_IMAGE_LOAD"
    docker save "$IMAGE" | sh -c "$DEMO_IMAGE_LOAD"
    return
  fi
  local node
  node=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')
  if docker inspect --type container "$node" >/dev/null 2>&1; then
    info "node $node is a container (kind mode): importing $IMAGE into its containerd"
    docker save "$IMAGE" | docker exec -i "$node" ctr -n k8s.io images import - >/dev/null
  elif [ "$DEMO_CONTEXT" = minikube ] && command -v minikube >/dev/null; then
    info "loading $IMAGE into minikube"
    minikube image load "$IMAGE"
  else
    info "node $node shares Docker's image store; no load needed"
  fi
}

# payments_table SCENARIOS VARIABLES_JSON INSTANCES_JSON
# Prints, for every synthetic payment in SCENARIOS (sample-data/scenarios.json), the expected outcome next to the
# state of its P2 process instance, then a one-line verdict. The expected outcome is the last scheme status the
# scenario reports: ACSC settles and RJCT rejects (both end COMPLETED), PDNG waits for an ops decision (ACTIVE).
# VARIABLES_JSON and INSTANCES_JSON are Camunda v2 search responses for the paymentId variable and for the
# pay-instant-exception instances. Returns 0 when every payment is as expected, 1 otherwise.
payments_table() {
  local scenarios=$1 vars=$2 insts=$3 id last expected want key state verdict total=0 bad=0
  printf '%-20s %-34s %-12s %s\n' PAYMENT EXPECTED ACTUAL CHECK
  while read -r id last; do
    case "$last" in
      ACSC) expected="settled (COMPLETED)";               want=COMPLETED ;;
      RJCT) expected="rejected (COMPLETED)";              want=COMPLETED ;;
      PDNG) expected="waits for an ops decision (ACTIVE)"; want=ACTIVE ;;
      *)    expected="unknown scheme status $last";        want="" ;;
    esac
    # The variable's processInstanceKey, then that instance's state.
    key=$(printf '%s' "$vars" | tr '{' '\n' | grep -F "\"value\":\"\\\"$id\\\"\"" \
      | sed -n 's/.*"processInstanceKey":"\{0,1\}\([0-9][0-9]*\).*/\1/p' | head -1 || true)
    state=""
    if [ -n "$key" ]; then
      state=$(printf '%s' "$insts" | tr '{' '\n' | grep "\"processInstanceKey\":\"\{0,1\}$key\"\{0,1\}[,}]" \
        | sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p' | head -1 || true)
    fi
    if [ -z "$state" ]; then verdict=MISSING
    elif [ "$state" = "$want" ]; then verdict=OK
    else verdict=WRONG; fi
    total=$((total + 1))
    [ "$verdict" = OK ] || bad=$((bad + 1))
    printf '%-20s %-34s %-12s %s\n' "$id" "$expected" "${state:-not found}" "$verdict"
  done < <(sed -n 's/^ *"\(demo-[a-z0-9-]*\)": *\[.*"\([A-Z]*\)" *\].*/\1 \2/p' "$scenarios")
  if [ "$total" -eq 0 ]; then
    warn "no payments found in $scenarios"
    return 1
  elif [ "$bad" -eq 0 ]; then
    info "all $total payments as expected"
  else
    warn "$bad of $total payments not as expected (MISSING: no process instance; WRONG: another state)"
    return 1
  fi
}
