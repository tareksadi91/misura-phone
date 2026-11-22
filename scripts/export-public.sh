#!/bin/sh
# Fresh single-commit export of the committed agent-phone tree for the public repo.
# Never publishes history: the private repo's history holds personal identifiers.
# Usage: scripts/export-public.sh <empty-output-dir>   (commit ref defaults to HEAD)
set -eu
out="${1:?usage: export-public.sh <empty-output-dir>}"
ref="${2:-HEAD}"
root="$(git rev-parse --show-toplevel)"
[ -z "$(ls -A "$out" 2>/dev/null)" ] || { echo "$out must be empty" >&2; exit 1; }
mkdir -p "$out"
git -C "$root" archive "$ref:agent-phone" | tar -x -C "$out"
git -C "$out" init -q -b main
git -C "$out" add -A
(cd "$out" && python3 scripts/check-oss.py >/dev/null) || { echo "check-oss failed on export" >&2; exit 1; }
git -C "$out" -c user.name="The Misura Authors" -c user.email="noreply@invalid" commit -q -m "Misura Phone: initial public release"
echo "Exported to $out (1 commit). Review, then add a remote and push yourself."
