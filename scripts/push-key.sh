#!/bin/sh
# Puts the phone's own Google login on it: the service account key (Vertex + Places only). After this the app makes its
# own access tokens and no laptop token is needed. The key file stays out of git (*.local.json). Default: sa.local.json next to docs/.
set -e
f="${1:-$(dirname "$0")/../sa.local.json}"
adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd sa --es value64 "$(base64 < "$f" | tr -d '\n')" >/dev/null
echo "key pushed"
