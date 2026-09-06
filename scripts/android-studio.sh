#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"
export STUDIO_PROPERTIES="$FONOTE_ROOT/scripts/studio.properties"
cd "$FONOTE_ROOT"

STUDIO_BIN="$FONOTE_ROOT/.tooling/android-studio/bin/studio"

# FONOTE_STUDIO_FG=1 keeps Studio attached to the terminal (logs on stdout).
if [[ -n "${FONOTE_STUDIO_FG:-}" ]]; then
  exec "$STUDIO_BIN" "$FONOTE_ROOT/android" "$@"
fi

LOG="$FONOTE_ROOT/.tooling/studio-logs/launcher.log"
mkdir -p "$(dirname -- "$LOG")"
setsid "$STUDIO_BIN" "$FONOTE_ROOT/android" "$@" </dev/null >>"$LOG" 2>&1 &
pid=$!
disown "$pid" 2>/dev/null || true
echo "Android Studio lance (pid $pid) - logs: ${LOG#$FONOTE_ROOT/}"
