#!/bin/sh
# Debug only: keeps the emulator/phone signed in to Vertex with your own short-lived token (expires after ~1 h).
# Optional: PROJECT=<id> also pushes the Vertex project.
# Usage: scripts/push-token.sh   (Ctrl-C to stop). Clear it: adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd token --es value ""
while true; do
  T=$(gcloud auth application-default print-access-token) || { echo "gcloud sign-in needed: gcloud auth application-default login"; exit 1; }
  adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd token --es value "$T" >/dev/null && echo "$(date +%H:%M) token pushed"
  if [ -n "${PROJECT:-}" ]; then
    adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd project --es value "$PROJECT" >/dev/null
  fi
  sleep 2700
done
