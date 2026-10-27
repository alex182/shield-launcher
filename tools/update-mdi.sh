#!/usr/bin/env bash
# Refresh the bundled Material Design Icons (the set Home Assistant's "mdi:"
# names refer to): the font plus a name → codepoint table.
#   tools/update-mdi.sh [version]
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:-7.4.47}"
OUT=app/src/main/assets/mdi
BASE="https://cdn.jsdelivr.net/npm/@mdi/font@$VERSION"
mkdir -p "$OUT"
curl -sfL -o "$OUT/materialdesignicons-webfont.ttf" "$BASE/fonts/materialdesignicons-webfont.ttf"
curl -sfL -o "$OUT/LICENSE" "$BASE/LICENSE"
# ".mdi-lightbulb::before { content: "\F0335"; }"  →  "lightbulb<TAB>F0335"
curl -sfL "$BASE/css/materialdesignicons.css" \
  | tr -d '\n' \
  | grep -oE '\.mdi-[a-z0-9-]+::before \{ *content: *"\\[0-9A-F]+"' \
  | sed -E 's/^\.mdi-([a-z0-9-]+)::before \{ *content: *"\\([0-9A-F]+)"/\1\t\2/' \
  > "$OUT/names.tsv"
echo "$VERSION" > "$OUT/VERSION"
echo "mdi $VERSION: $(wc -l < "$OUT/names.tsv") icons"
