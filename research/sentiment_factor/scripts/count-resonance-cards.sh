#!/usr/bin/env bash
#
# count-resonance-cards.sh -- autoresearch Metric / Verify shared script.
#
# Counts qualified resonance cards under out/resonance_cards/.
# One card represents one (factor, target Y, horizon, band, state) conclusion.
# qualified=true must satisfy the 12 hard gates defined in the execution handbook
# (private/research-docs/sentiment/02-research-execution-handbook.md).
#
# Output: a single integer on stdout. Empty or missing card directories output 0.

set -euo pipefail

cd "$(dirname "$0")/.."
shopt -s nullglob

count=0
for f in out/resonance_cards/*.json; do
  if grep -q '"qualified"[[:space:]]*:[[:space:]]*true' "$f"; then
    count=$((count + 1))
  fi
done

echo "$count"
