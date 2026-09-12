#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"
bash "$FONOTE_ROOT/scripts/build-android.sh" --offline :app:assembleDebugAndroidTest
# APK names carry the version, which changes: take the most recent one in each folder rather
# than a hardcoded name, which would go stale at the next number.
apk=$(ls -t "$FONOTE_ROOT"/android/app/build/outputs/apk/debug/*.apk | head -1)
tests=$(ls -t "$FONOTE_ROOT"/android/app/build/outputs/apk/androidTest/debug/*.apk | head -1)
adb install -r "$apk"
adb install -r "$tests"
result=$(adb shell am instrument -w fr.fonote.test/fr.fonote.OfflineChecks)
printf '%s\n' "$result"
[[ "$result" == *"Offline checks passed:"* ]]
