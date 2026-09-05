#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"
cd "$FONOTE_ROOT/android"
exec ./gradlew :app:assembleDebug :app:lintDebug "$@"
