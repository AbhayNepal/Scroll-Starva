#!/usr/bin/env bash

# Prepare a connected Android device for attaching IntelliJ's remote debugger.
# This script builds/installs the debug app, tells Android to wait for a debugger,
# launches the app, and forwards its JDWP connection to localhost:8700.
#
# Usage:
#   ./scripts/android-debug.sh start
#   ./scripts/android-debug.sh stop
#
# If more than one device/emulator is connected, choose one like this:
#   ANDROID_SERIAL=13150314A3031291 ./scripts/android-debug.sh start

set -euo pipefail

APP_ID="com.scrollstarva.app"
ACTIVITY="$APP_ID/.MainActivity"
DEBUG_PORT="8700"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"

usage() {
    echo "Usage: $0 {start|stop}" >&2
    echo "  start  Build/install the debug app and prepare IntelliJ attachment." >&2
    echo "  stop   Clear Android's wait-for-debugger setting and remove port forwarding." >&2
}

if [[ $# -ne 1 ]]; then
    usage
    exit 2
fi

ACTION="$1"
if [[ "$ACTION" != "start" && "$ACTION" != "stop" ]]; then
    usage
    exit 2
fi

if ! command -v "$ADB" >/dev/null 2>&1; then
    echo "Error: adb was not found. Install Android platform-tools or set ADB to its path." >&2
    exit 1
fi

# Use ANDROID_SERIAL when provided; otherwise require exactly one connected device.
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    DEVICE_LIST="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    DEVICES=()
    if [[ -n "$DEVICE_LIST" ]]; then
        while IFS= read -r device; do
            DEVICES+=("$device")
        done <<< "$DEVICE_LIST"
    fi
    if [[ ${#DEVICES[@]} -ne 1 ]]; then
        echo "Error: expected exactly one authorized Android device; found ${#DEVICES[@]}." >&2
        echo "Run 'adb devices' and authorize your phone, or set ANDROID_SERIAL to its device ID." >&2
        exit 1
    fi
    ANDROID_SERIAL="${DEVICES[0]}"
fi

ADB_DEVICE=("$ADB" -s "$ANDROID_SERIAL")
if [[ "$("${ADB_DEVICE[@]}" get-state 2>/dev/null || true)" != "device" ]]; then
    echo "Error: Android device '$ANDROID_SERIAL' is not connected/authorized." >&2
    exit 1
fi

remove_debug_forward() {
    # Only remove this script's port mapping if it currently exists.
    if "${ADB_DEVICE[@]}" forward --list | awk -v port="tcp:$DEBUG_PORT" '$2 == port { found = 1 } END { exit !found }'; then
        "${ADB_DEVICE[@]}" forward --remove "tcp:$DEBUG_PORT"
    fi
}

START_FINISHED=0
cleanup_failed_start() {
    result=$?
    if [[ "$ACTION" == "start" && $START_FINISHED -eq 0 && $result -ne 0 ]]; then
        echo "Setup did not finish; clearing Android's wait-for-debugger setting." >&2
        "${ADB_DEVICE[@]}" shell am clear-debug-app >/dev/null 2>&1 || true
        remove_debug_forward >/dev/null 2>&1 || true
    fi
}
trap cleanup_failed_start EXIT

if [[ "$ACTION" == "stop" ]]; then
    # Android keeps this setting after the app closes, so explicitly clear it.
    "${ADB_DEVICE[@]}" shell am clear-debug-app
    remove_debug_forward
    echo "Debugger wait setting cleared and localhost:$DEBUG_PORT forwarding removed."
    echo "If the app is paused in IntelliJ, press Resume or Stop there."
    exit 0
fi

echo "Building and installing the debug app on $ANDROID_SERIAL..."
cd "$ROOT_DIR"
./gradlew assembleDebug
"${ADB_DEVICE[@]}" install -r "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"

# Remove stale debugger state/port mapping before configuring a fresh launch.
"${ADB_DEVICE[@]}" shell am clear-debug-app
remove_debug_forward

# The -w flag makes the app process wait at startup until IntelliJ attaches.
"${ADB_DEVICE[@]}" shell am set-debug-app -w "$APP_ID"
"${ADB_DEVICE[@]}" shell am force-stop "$APP_ID"
"${ADB_DEVICE[@]}" shell am start -n "$ACTIVITY"

echo "Waiting for the app process to start..."
PID=""
attempt=0
while [[ $attempt -lt 30 ]]; do
    PID="$("${ADB_DEVICE[@]}" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | awk '{ print $1 }' || true)"
    if [[ -n "$PID" ]]; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 1
done

if [[ -z "$PID" ]]; then
    "${ADB_DEVICE[@]}" shell am clear-debug-app
    echo "Error: app process did not start. Check installation and device logs." >&2
    exit 1
fi

"${ADB_DEVICE[@]}" forward "tcp:$DEBUG_PORT" "jdwp:$PID"
START_FINISHED=1

echo
echo "The app is waiting for IntelliJ to attach (PID $PID). It may look frozen for now."
echo "In IntelliJ, start your Remote JVM Debug configuration with host localhost and port $DEBUG_PORT."
echo "When IntelliJ attaches, the app will continue. Reproduce the issue on the phone."
echo
echo "When finished, detach/stop the debugger, then run:"
echo "  $0 stop"
