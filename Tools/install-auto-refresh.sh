#!/bin/bash
# Turns on the twice-daily automatic re-sign. Run this once, after Xcode is
# installed and you have run Stride from Xcode at least once.
set -euo pipefail

LABEL="com.jashith.stride.refresh"
AGENT_DIR="$HOME/Library/LaunchAgents"
TARGET="$AGENT_DIR/$LABEL.plist"
SOURCE="$HOME/Stride/Tools/$LABEL.plist"

if ! /usr/bin/xcrun --find xcodebuild >/dev/null 2>&1; then
    echo "Xcode is not installed yet. Install it, run Stride on your iPhone once, then run this again."
    exit 1
fi

mkdir -p "$AGENT_DIR" "$HOME/Stride/.build"
sed "s|__HOME__|$HOME|g" "$SOURCE" > "$TARGET"
chmod +x "$HOME/Stride/Tools/stride-refresh.sh"

# Replace any previous copy.
launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$UID" "$TARGET"

echo "Auto-refresh is on. It runs at 04:15 and 19:15 every day."
echo
echo "Doing one refresh now so you can see it work:"
"$HOME/Stride/Tools/stride-refresh.sh" || true
echo
tail -5 "$HOME/Stride/.build/refresh.log" 2>/dev/null || echo "(no log yet)"
