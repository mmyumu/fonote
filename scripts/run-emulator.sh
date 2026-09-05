#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"
exec emulator -avd Fonote_API_35 -no-snapshot -gpu software "$@"
