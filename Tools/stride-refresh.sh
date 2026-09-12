#!/bin/bash
#
# Rebuilds and reinstalls Stride on your iPhone so the free 7-day signature
# never gets a chance to expire. Runs unattended from a launchd agent.
#
# It needs the phone paired to this Mac in Xcode with "Connect via network"
# ticked (Xcode → Window → Devices and Simulators). After that it works over
# Wi-Fi with no cable and without opening Xcode.

set -uo pipefail

PROJECT="$HOME/Stride/Stride.xcodeproj"
SCHEME="Stride"
BUILD_DIR="$HOME/Stride/.build"
APP_PATH="$BUILD_DIR/Build/Products/Debug-iphoneos/$SCHEME.app"
LOG="$BUILD_DIR/refresh.log"
STAMP="$BUILD_DIR/last-success"
DEVICE_FILE="$HOME/Stride/Tools/device.txt"

# Days after a successful install before we start nagging. Free profiles die
# at 7, so 5 leaves a comfortable margin.
WARN_AFTER_DAYS=5

mkdir -p "$BUILD_DIR"

log() {
    printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" >> "$LOG"
}

notify() {
    /usr/bin/osascript -e "display notification \"$1\" with title \"Stride\" sound name \"Ping\"" >/dev/null 2>&1
}

# Warn only when the signature is genuinely close to lapsing, so a phone that is
# simply off the network for a day stays silent.
check_staleness() {
    [ -f "$STAMP" ] || return 0
    local last now days
    last=$(cat "$STAMP" 2>/dev/null) || return 0
    now=$(date +%s)
    days=$(( (now - last) / 86400 ))
    if [ "$days" -ge "$WARN_AFTER_DAYS" ]; then
        local left=$(( 7 - days ))
        if [ "$left" -gt 0 ]; then
            notify "Not refreshed for $days days — expires in $left. Put your iPhone on Wi-Fi near this Mac, or plug it in."
        else
            notify "The signature has expired. Plug your iPhone in and run Tools/stride-refresh.sh."
        fi
    fi
}

if ! /usr/bin/xcrun --find xcodebuild >/dev/null 2>&1; then
    log "Xcode is not installed — nothing to do."
    exit 0
fi

# Which iPhone? A UDID in Tools/device.txt wins; otherwise take the first
# reachable one.
DEVICE_ID=""
if [ -f "$DEVICE_FILE" ]; then
    DEVICE_ID=$(tr -d '[:space:]' < "$DEVICE_FILE")
fi

DEVICE_JSON="$BUILD_DIR/devices.json"
if ! /usr/bin/xcrun devicectl list devices --json-output "$DEVICE_JSON" >/dev/null 2>&1; then
    log "Could not list devices."
    check_staleness
    exit 0
fi

if [ -z "$DEVICE_ID" ]; then
    DEVICE_ID=$(/usr/bin/python3 - "$DEVICE_JSON" <<'PY'
import json, sys
try:
    devices = json.load(open(sys.argv[1]))["result"]["devices"]
except Exception:
    sys.exit(0)
# Stride is an iPhone app, so an iPad or a watch on the network is not a target.
candidates = []
for d in devices:
    hardware = d.get("hardwareProperties", {})
    conn = d.get("connectionProperties", {})
    if hardware.get("platform") != "iOS":
        continue
    if hardware.get("deviceType") not in (None, "iPhone"):
        continue
    if conn.get("pairingState") not in (None, "paired"):
        continue
    candidates.append((conn.get("tunnelState"), d.get("identifier", "")))

# A live tunnel means the phone is reachable now. Otherwise still try the paired
# one, because the tunnel is often only brought up on demand.
for state, identifier in candidates:
    if state == "connected":
        print(identifier)
        break
else:
    if candidates:
        print(candidates[0][1])
PY
)
fi

if [ -z "$DEVICE_ID" ]; then
    log "No reachable iPhone. Will try again on the next run."
    check_staleness
    exit 0
fi

log "Building for device $DEVICE_ID"
BUILD_OUTPUT=$(/usr/bin/xcrun xcodebuild \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -configuration Debug \
    -destination "generic/platform=iOS" \
    -derivedDataPath "$BUILD_DIR" \
    -allowProvisioningUpdates \
    -quiet \
    build 2>&1)
BUILD_STATUS=$?

if [ $BUILD_STATUS -ne 0 ]; then
    log "Build failed:"
    printf '%s\n' "$BUILD_OUTPUT" | tail -30 >> "$LOG"
    notify "Rebuild failed. See Stride/.build/refresh.log"
    exit 1
fi

if [ ! -d "$APP_PATH" ]; then
    log "Built, but $APP_PATH is missing."
    notify "Build produced no app. See Stride/.build/refresh.log"
    exit 1
fi

log "Installing"
INSTALL_OUTPUT=$(/usr/bin/xcrun devicectl device install app \
    --device "$DEVICE_ID" "$APP_PATH" 2>&1)
INSTALL_STATUS=$?

if [ $INSTALL_STATUS -ne 0 ]; then
    # A phone that is simply not around is the normal case for a scheduled run,
    # not a failure worth shouting about.
    if printf '%s' "$INSTALL_OUTPUT" | grep -qE "could not be established|Connection reset|not connected|error 4000"; then
        log "iPhone not reachable right now — will try again on the next run."
        check_staleness
        exit 0
    fi
    log "Install failed:"
    printf '%s\n' "$INSTALL_OUTPUT" | tail -20 >> "$LOG"
    notify "Could not install on your iPhone. See Stride/.build/refresh.log"
    check_staleness
    exit 1
fi

date +%s > "$STAMP"
log "Refreshed successfully — good for another 7 days."

# Keep the log from growing without bound.
if [ -f "$LOG" ] && [ "$(wc -l < "$LOG")" -gt 2000 ]; then
    tail -500 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi

exit 0
