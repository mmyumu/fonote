#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"
export STUDIO_PROPERTIES="$FONOTE_ROOT/scripts/studio.properties"
cd "$FONOTE_ROOT"
exec "$FONOTE_ROOT/.tooling/android-studio/bin/studio" "$FONOTE_ROOT/android" "$@"
