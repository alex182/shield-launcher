#!/usr/bin/env bash
# Copy the generated backgrounds (output/backgrounds/) to the NFS share the
# launcher reads them from, through this machine's own mount of it. The
# SHIELDs pick up the new manifest.json at their next check (five minutes).
#   DEST  the directory as mounted here; default
#         ~/nas_lake/shield-launcher/backgrounds, which is
#         10.10.4.2:/lake/media/shield-launcher/backgrounds on the NAS.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=output/backgrounds
DEST="${DEST:-$HOME/nas_lake/shield-launcher/backgrounds}"
[ -f "$SRC/manifest.json" ] || { echo "no $SRC/manifest.json: run scripts/generate_backgrounds.py first" >&2; exit 1; }
mkdir -p "$DEST"
# Images first and the manifest last, so a SHIELD never sees a manifest that
# lists files not there yet. Readable by anyone: the TVs read as the export's
# squashed, read-only user (README, "Background images on NFS").
rsync -rt --delete --exclude manifest.json --chmod=D755,F644 "$SRC/" "$DEST/"
rsync -t --chmod=F644 "$SRC/manifest.json" "$DEST/manifest.json"
echo "published $(find "$DEST" -name '*.webp' | wc -l) images to $DEST"
