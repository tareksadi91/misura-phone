#!/bin/sh
# Debug only: keeps the emulator/phone signed in to Vertex with your own short-lived token (expires after ~1 h).
# The token goes over stdin (../../scripts/push-secret.sh), never as a process argument on this computer. Needs ANDROID_SERIAL.
# Optional: PROJECT=<id> also pushes the Vertex project (not a secret).
# Usage: scripts/push-token.sh   (Ctrl-C to stop). Clear it: adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd token --es value ""
push="$(dirname "$0")/../../scripts/push-secret.sh"
while true; do
  T=$(gcloud auth application-default print-access-token 2>/dev/null) || { echo "gcloud sign-in needed: gcloud auth application-default login"; exit 1; }
  printf '%s' "$T" | bash "$push" token >/dev/null || { echo "Token push failed (ANDROID_SERIAL set? phone connected?)"; exit 1; }
  T=
  echo "$(date +%H:%M) token pushed"
  if [ -n "${PROJECT:-}" ]; then
    adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd project --es value "$PROJECT" >/dev/null
  fi
  sleep 2700
done
