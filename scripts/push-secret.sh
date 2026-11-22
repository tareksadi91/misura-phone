#!/usr/bin/env bash
# Debug builds only. Sends one credential to the phone's Misura without putting it in this computer's process
# arguments or printing it: the secret is read from stdin, validated, and expanded only inside the phone's shell.
# Kinds the debug receiver accepts: sa (service-account JSON), exa (Exa key), token (short-lived Vertex token).
# Gemini/OpenAI/Anthropic API keys have no adb setter: enter those in Misura settings.
#
#   export ANDROID_SERIAL=YOUR_PHONE_SERIAL
#   bash scripts/push-secret.sh sa < /absolute/path/service-account.json
#   ( trap 'pbcopy </dev/null' EXIT; pbpaste | bash scripts/push-secret.sh exa )   # macOS clipboard, cleared after
#
# Limits: on the phone the value still passes through `am` arguments (visible to adb shell/root and privileged
# tools); clipboard managers may keep history; system logs are not under our control. Nothing confirms success:
# reopen Misura settings, then prove access with a real request.
set +xv
set -euo pipefail
: "${ANDROID_SERIAL:?Select the target phone first: export ANDROID_SERIAL=...}"
kind=${1:?Expected sa, exa or token}
case "$kind" in sa|exa|token) ;; *) echo "Expected sa, exa or token" >&2; exit 2 ;; esac

{
  printf '%s\n' "$kind"
  python3 -c '
import base64, json, sys
try:
    k = sys.argv[1]
    raw = sys.stdin.buffer.read(65537)
    if not 0 < len(raw) <= 65536:
        raise ValueError()
    raw = raw.strip()
    if k == "sa":
        j = json.loads(raw)
        if j.get("type") != "service_account" or not all(
            isinstance(j.get(f), str) and j[f] for f in ("project_id", "client_email", "private_key")):
            raise ValueError()
    elif not raw or len(raw) > (2048 if k == "exa" else 16384) or any(c < 33 or c > 126 for c in raw):
        raise ValueError()
except Exception:
    sys.exit("Invalid or empty input; nothing imported.")
print(base64.b64encode(raw).decode("ascii"))
' "$kind"
} | adb -s "$ANDROID_SERIAL" shell -T '
set +xv
exec >/dev/null 2>&1
IFS= read -r kind || exit 2
IFS= read -r encoded || exit 2
[ -n "$encoded" ] || exit 2
case "$encoded" in *[!A-Za-z0-9+/=]*) exit 2 ;; esac
case "$kind" in
  sa) extra=value64; value=$encoded ;;
  exa|token) extra=value; value=$(printf %s "$encoded" | base64 -d) || exit 2 ;;
  *) exit 2 ;;
esac
[ -n "$value" ] || exit 2
am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd "$kind" --es "$extra" "$value" </dev/null
'
echo "$kind sent to $ANDROID_SERIAL (not yet proof it works: reopen Misura settings, then run a real request)"
