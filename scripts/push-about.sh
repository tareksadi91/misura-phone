#!/bin/sh
# Puts the user's briefing (who they are, taste, how to work with them) on the phone; Misura reads it on every model call.
# The file stays out of git (*.local.md). No argument = about.local.md next to docs/. An empty file brings the built-in default back.
set -e
f="${1:-$(dirname "$0")/../about.local.md}"
adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd about --es value64 "$(base64 < "$f" | tr -d '\n')" >/dev/null
echo "pushed $(wc -c < "$f" | tr -d ' ') chars (Misura uses the first 6000)"
