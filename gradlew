#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "$ROOT"/.tools/jdk/*/Contents/Home; do
        if [ -x "$candidate/bin/java" ]; then JAVA_HOME="$candidate"; export JAVA_HOME; break; fi
    done
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$ROOT/.tools/android-sdk" ]; then
    ANDROID_HOME="$ROOT/.tools/android-sdk"; export ANDROID_HOME
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA_BIN" -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
