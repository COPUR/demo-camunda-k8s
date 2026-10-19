# Sourced by install.sh, open.sh, status.sh and uninstall.sh.
# 1. Picks the cluster and refuses any whose API server is not on this machine (throwaway local clusters only).
# 2. Pins every later kubectl and helm call to that one cluster through a private, minified kubeconfig, so a
#    context switch in another terminal mid-run cannot redirect them. Your own current context is left as it is.
# 3. Knows how to hand the locally built image to the cluster's container runtime.

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
