#!/usr/bin/env bash
# Source this file from any directory to use the project-local Android toolchain.
FONOTE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$FONOTE_ROOT/.tooling/jdk"
export ANDROID_HOME="$FONOTE_ROOT/.tooling/android-sdk"
export GRADLE_USER_HOME="$FONOTE_ROOT/.tooling/gradle-cache"
export ANDROID_USER_HOME="$FONOTE_ROOT/.tooling/android-user"
export ANDROID_AVD_HOME="$FONOTE_ROOT/.tooling/avd"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$FONOTE_ROOT/.tooling/gradle-8.9/bin:$PATH"
