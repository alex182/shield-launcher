#!/usr/bin/env python3
"""Generate source images for the music visualizer through ComfyUI.

Each prompt in scripts/visualizer_images/config.json is drawn by FLUX.2 Klein
4B (the backgrounds' text-to-image workflow) as a glowing subject on pure
black. The black is then keyed out to transparency (brightness becomes
alpha), so on the TV only the subject is painted into the presets and its
glow fades softly into them. Results are PNGs at 1280 px wide, the size the
config page would upload anyway.

  generate_visualizer_images.py                      # everything still missing
  generate_visualizer_images.py --only jellyfish     # just these (comma-separated)
  generate_visualizer_images.py --force              # redo all
  generate_visualizer_images.py --rekey              # re-key the raw images only
  generate_visualizer_images.py --upload http://192.168.1.209:8080 --only jellyfish,mandala
  generate_visualizer_images.py --install-defaults   # make them the APK's built-in set
  generate_visualizer_images.py --add-default ~/Pictures/logo.png photo.jpg [--key-black]

Raw renders go to output/visualizer/raw/, keyed PNGs to output/visualizer/,
and a contact sheet (on a colour gradient, to show the transparency) to
output/visualizer/sheet.jpg. --upload sends keyed PNGs to a SHIELD's config
page, where they join its source images straight away. --install-defaults
replaces the built-in set (app/src/main/assets/visualizer/defaults/, named
NN_<name>.png in config order, which is the order they cycle in); commit it
and it ships with the next build. --add-default puts your own pictures into
the built-in set as well (scaled to 1280 px; PNG if they have transparency,
JPEG if not; --key-black also keys out a black background, as for the
generated ones); --install-defaults leaves those alone. To drop one, delete
its file from the defaults folder.
"""

import argparse
import json
import re
import sys
import time
from pathlib import Path

import numpy as np
import requests
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_backgrounds import Comfy, load_workflow  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
CONFIG = REPO / "scripts" / "visualizer_images" / "config.json"
OUT = REPO / "output" / "visualizer"
RAW = OUT / "raw"
DEFAULTS = REPO / "app" / "src" / "main" / "assets" / "visualizer" / "defaults"
WIDTH = 1280
# Brightness (max of R, G, B, 0–1) below LO is fully transparent, above HI
# fully opaque, smoothly between: keeps glows soft without a grey haze.
LO, HI = 0.04, 0.32


def render(comfy, cfg, item):
    wf = load_workflow("flux2_klein_4b_t2i")
    wf["74"]["inputs"]["text"] = f"{item['prompt']} {cfg['style']}"
    wf["67"]["inputs"]["text"] = cfg["negative"]
    wf["73"]["inputs"]["noise_seed"] = item["seed"]
    for node in ("62", "66"):
        wf[node]["inputs"]["width"] = cfg["width"]
        wf[node]["inputs"]["height"] = cfg["height"]
    wf["9"]["inputs"]["filename_prefix"] = "visualizer/" + item["name"]
    return comfy.run(wf)


def key_black(raw_path, out_path):
    """Black to transparent: alpha from brightness, colour un-darkened to match."""
    im = Image.open(raw_path).convert("RGB")
    if im.width != WIDTH:
        im = im.resize((WIDTH, round(im.height * WIDTH / im.width)), Image.LANCZOS)
    rgb = np.asarray(im).astype(np.float32) / 255
    v = rgb.max(axis=2)
    t = np.clip((v - LO) / (HI - LO), 0, 1)
    alpha = t * t * (3 - 2 * t)
    # A glow on black is its colour times its coverage; divide the coverage
    # back out, or edges would carry a dark fringe into the visuals.
    colour = np.clip(rgb / np.maximum(alpha, 1e-3)[..., None], 0, 1)
    colour = np.where(alpha[..., None] > 0, colour, 0)
    rgba = np.dstack([colour, alpha])
    Image.fromarray((rgba * 255 + 0.5).astype(np.uint8), "RGBA").save(out_path, optimize=True)


def add_default(src, key):
    """One of your own pictures into the built-in set; returns where it went."""
    stem = re.sub(r"[^a-z0-9]+", "_", src.stem.lower()).strip("_") or "image"
    if re.match(r"\d\d_", stem):   # that pattern is the generated set's, which --install-defaults replaces
        stem = "my_" + stem
    DEFAULTS.mkdir(parents=True, exist_ok=True)
    if key:
        out = DEFAULTS / f"{stem}.png"
        key_black(src, out)
        return out
    im = Image.open(src)
    im.load()
    has_alpha = im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info)
    im = im.convert("RGBA" if has_alpha else "RGB")
    if has_alpha and im.getchannel("A").getextrema()[0] == 255:
        im, has_alpha = im.convert("RGB"), False
    im.thumbnail((WIDTH, WIDTH), Image.LANCZOS)
    out = DEFAULTS / f"{stem}.{'png' if has_alpha else 'jpg'}"
    for other in DEFAULTS.glob(f"{stem}.*"):
        other.unlink()
    if has_alpha:
        im.save(out, optimize=True)
    else:
        im.save(out, quality=90)
    return out


def contact_sheet(paths, out, cols=4, thumb=(320, 183)):
    """Keyed images over a colour gradient, so what's transparent shows."""
    pad, lh = 8, 18
    rows = (len(paths) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * (thumb[0] + pad) + pad, rows * (thumb[1] + lh + pad) + pad), (24, 24, 24))
    gx = np.linspace(0, 1, thumb[0])[None, :, None]
    gy = np.linspace(0, 1, thumb[1])[:, None, None]
    grad = (np.concatenate([0.35 * gx + 0.05 + 0 * gy, 0.05 + 0.25 * gy + 0 * gx, 0.35 - 0.3 * gx + 0 * gy], 2) * 255)
    bg = Image.fromarray(grad.astype(np.uint8), "RGB")
    d = ImageDraw.Draw(sheet)
    for i, p in enumerate(paths):
        x = pad + (i % cols) * (thumb[0] + pad)
        y = pad + (i // cols) * (thumb[1] + lh + pad)
        im = Image.open(p).convert("RGBA").resize(thumb, Image.LANCZOS)
        tile = bg.copy()
        tile.paste(im, (0, 0), im)
        sheet.paste(tile, (x, y + lh))
        d.text((x, y + 3), p.stem, fill=(230, 230, 230))
    sheet.save(out, quality=88)


def upload(base, path):
    r = requests.post(base.rstrip("/") + "/api/visualizer/image", data=path.read_bytes(),
                      headers={"Content-Type": "image/png"}, timeout=60)
    r.raise_for_status()
    j = r.json()
    print(f"  {path.name}: {'ok' if j.get('ok') else 'FAILED'} {j.get('message', '')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--comfy", default="http://127.0.0.1:8188")
    ap.add_argument("--only", help="comma-separated names from config.json")
    ap.add_argument("--force", action="store_true", help="re-render even if the raw image exists")
    ap.add_argument("--rekey", action="store_true", help="don't render; re-key the raw images")
    ap.add_argument("--upload", metavar="URL", help="a SHIELD's config page, e.g. http://192.168.1.209:8080")
    ap.add_argument("--install-defaults", action="store_true",
                    help="copy the keyed PNGs (all of config.json) into the APK's built-in set")
    ap.add_argument("--add-default", nargs="+", type=Path, metavar="FILE",
                    help="add your own pictures to the built-in set, then stop")
    ap.add_argument("--key-black", action="store_true", help="with --add-default: key out a black background")
    args = ap.parse_args()

    if args.add_default:
        for src in args.add_default:
            if not src.is_file():
                sys.exit(f"No such file: {src}")
            out = add_default(src, args.key_black)
            print(f"{src} -> {out.relative_to(REPO)} ({out.stat().st_size // 1024} KB)")
        print("Commit the defaults folder; they reach the TVs with the next ./build.sh install.")
        return

    cfg = json.loads(CONFIG.read_text())
    items = cfg["images"]
    if args.only:
        want = set(args.only.split(","))
        unknown = want - {i["name"] for i in items}
        if unknown:
            sys.exit(f"Not in config.json: {', '.join(sorted(unknown))}")
        items = [i for i in items if i["name"] in want]
    RAW.mkdir(parents=True, exist_ok=True)

    if not args.rekey:
        todo = [i for i in items if args.force or not (RAW / f"{i['name']}_{i['seed']}.png").exists()]
        if todo:
            comfy = Comfy(args.comfy)
            comfy.check()
        for n, item in enumerate(todo, 1):
            t0 = time.time()
            print(f"[{n}/{len(todo)}] {item['name']} (seed {item['seed']})…", end=" ", flush=True)
            (RAW / f"{item['name']}_{item['seed']}.png").write_bytes(render(comfy, cfg, item))
            print(f"{time.time() - t0:.0f}s")

    keyed = []
    for item in items:
        raw = RAW / f"{item['name']}_{item['seed']}.png"
        if raw.exists():
            out = OUT / f"{item['name']}.png"
            key_black(raw, out)
            keyed.append(out)
    if keyed:
        contact_sheet(keyed, OUT / "sheet.jpg")
        print(f"{len(keyed)} keyed PNGs in {OUT.relative_to(REPO)}/, sheet: {(OUT / 'sheet.jpg').relative_to(REPO)}")

    if args.install_defaults:
        if args.only:
            sys.exit("--install-defaults replaces the whole set; run it without --only")
        missing = [i["name"] for i in cfg["images"] if not (OUT / f"{i['name']}.png").exists()]
        if missing:
            sys.exit(f"Not generated yet: {', '.join(missing)}")
        DEFAULTS.mkdir(parents=True, exist_ok=True)
        # Only the generated set (NN_name.png); pictures added with --add-default stay.
        for old in DEFAULTS.glob("*.png"):
            if re.match(r"\d\d_", old.name):
                old.unlink()
        for n, item in enumerate(cfg["images"], 1):
            (DEFAULTS / f"{n:02d}_{item['name']}.png").write_bytes((OUT / f"{item['name']}.png").read_bytes())
        print(f"{len(cfg['images'])} built-in images in {DEFAULTS.relative_to(REPO)}/")

    if args.upload:
        print(f"Uploading to {args.upload}")
        for p in keyed:
            upload(args.upload, p)


if __name__ == "__main__":
    main()
