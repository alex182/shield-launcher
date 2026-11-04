#!/usr/bin/env bash
# Refresh the bundled butterchurn (Milkdrop in WebGL) and its main preset
# pack, used by the music visualizer.
#   tools/update-butterchurn.sh [version] [presets-version]
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:-2.6.7}"
PRESETS="${2:-2.4.7}"
OUT=app/src/main/assets/visualizer
mkdir -p "$OUT"
curl -sfL -o "$OUT/butterchurn.min.js" "https://cdn.jsdelivr.net/npm/butterchurn@$VERSION/lib/butterchurn.min.js"
curl -sfL -o "$OUT/butterchurnPresets.min.js" \
  "https://cdn.jsdelivr.net/npm/butterchurn-presets@$PRESETS/lib/butterchurnPresets.min.js"
curl -sfL -o "$OUT/LICENSE" "https://cdn.jsdelivr.net/npm/butterchurn@$VERSION/LICENSE"
echo "butterchurn $VERSION, butterchurn-presets $PRESETS" > "$OUT/VERSION"
echo "butterchurn $VERSION, presets $PRESETS: $(du -ch "$OUT"/*.js | tail -1 | cut -f1)"
