#!/bin/bash
# Turns the automatic re-sign back off.
set -uo pipefail
LABEL="com.jashith.stride.refresh"
launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
echo "Auto-refresh removed."
