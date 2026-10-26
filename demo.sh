#!/usr/bin/env bash
# One entry point for the demo: ./demo.sh up | open | login | status | down
# The other scripts keep working on their own; this only dispatches to them.
set -euo pipefail
cd "$(dirname "$0")"

# login copies the generated presenter password to the clipboard without printing it. Without a clipboard tool it
# prints the command that reads the Secret instead, so the value never lands in a log by accident.
login() {
  . lib/common.sh
  select_cluster
  local copy=""
  if command -v pbcopy >/dev/null; then copy=pbcopy
  elif command -v clip.exe >/dev/null; then copy=clip.exe
  elif command -v wl-copy >/dev/null; then copy=wl-copy
  elif command -v xclip >/dev/null; then copy="xclip -selection clipboard"; fi
  if [ -z "$copy" ]; then
    echo "No clipboard tool found. Read the password yourself:"
    echo "  kubectl --context $DEMO_CONTEXT -n $NS get secret demo-presenter -o jsonpath='{.data.password}' | base64 -d; echo"
    return
  fi
  kubectl -n "$NS" get secret demo-presenter -o jsonpath='{.data.password}' | base64 -d | $copy
  echo "Password of user \"presenter\" copied to the clipboard."
}

case "${1:-}" in
  up)     ./install.sh ;;
  open)   ./open.sh ;;
  login)  login ;;
  status) ./status.sh ;;
  down)   ./uninstall.sh ;;
  *) echo "usage: ./demo.sh up|open|login|status|down" >&2; exit 2 ;;
esac
