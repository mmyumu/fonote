#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"
bash "$FONOTE_ROOT/scripts/build-android.sh" --offline :app:assembleDebugAndroidTest
adb install -r "$FONOTE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
adb install -r "$FONOTE_ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
result=$(adb shell am instrument -w fr.fonote.test/fr.fonote.OfflineChecks)
printf '%s\n' "$result"
[[ "$result" == *"Offline checks passed:"* ]]
