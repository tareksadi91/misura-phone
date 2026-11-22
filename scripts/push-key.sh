#!/bin/sh
# Puts the phone's own Google login on it: the service account key (Vertex + Places only). After this the app makes its
# own access tokens and no laptop token is needed. The key file stays out of git (*.local.json). Default: sa.local.json next to docs/.
# Sends the key over stdin (see push-secret.sh), never as a process argument on this computer. Needs ANDROID_SERIAL.
set -e
f="${1:-$(dirname "$0")/../sa.local.json}"
bash "$(dirname "$0")/push-secret.sh" sa < "$f"
