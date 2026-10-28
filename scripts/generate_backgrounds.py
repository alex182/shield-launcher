#!/usr/bin/env python3
"""Generate the Flint Hills launcher backgrounds through ComfyUI's HTTP API.

Every image is a chain of FLUX.2 Klein 4B edits from one chosen base, so the
whole set stays the same scene:

  base (t2i) -> focal -> season -> time (day/night) -> weather condition
                                                   \\-> holiday (from <season>/<time>/clear)
                                                         -> holiday weather (every other condition)

Each stage's output is kept in output/work/ so any stage can be redone without
redoing earlier ones. Finals are upscaled to 4K and written as WebP to
output/backgrounds/. Config (seeds, style, prompts) lives in
scripts/backgrounds/config.json.

  generate_backgrounds.py --stage base            # seed candidates, then stop
  generate_backgrounds.py --choose-base 303       # keep candidate seed 303
  generate_backgrounds.py --preview               # windmill/autumn chain, then stop
  generate_backgrounds.py                         # everything still missing
"""

import argparse
import hashlib
import json
import shutil
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import requests
from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parent.parent
CFG_DIR = REPO / "scripts" / "backgrounds"
WORKFLOWS = CFG_DIR / "workflows"
CHOSEN_BASE = CFG_DIR / "chosen_base.png"
CHOSEN_META = CFG_DIR / "chosen_base.json"
WORK = REPO / "output" / "work"
FINAL = REPO / "output" / "backgrounds"
REVIEW = REPO / "output" / "review"

STAGES = ["focal", "season", "time", "weather", "holiday", "holiday_weather", "final"]
PREVIEW = {"focal": ["windmill"], "season": ["autumn"], "time": ["day", "night"]}
# Rough seconds per job, used for the ETA until real timings come in.
DEFAULT_SECONDS = {"t2i": 50, "edit": 15, "copy": 0, "final": 15}


# ---------------------------------------------------------------- ComfyUI

class Comfy:
    def __init__(self, url):
        self.url = url.rstrip("/")
        self.client_id = str(uuid.uuid4())

    def check(self):
        try:
            stats = requests.get(f"{self.url}/system_stats", timeout=5).json()
        except requests.RequestException as e:
            sys.exit(f"ComfyUI isn't reachable at {self.url} ({e}).\n"
                     "Start it with: cd comfyui && docker compose up -d")
        dev = stats.get("devices", [{}])[0]
        print(f"ComfyUI {stats['system'].get('comfyui_version', '?')} on {dev.get('name', '?')}")

    def upload(self, path):
        # Name uploads by content so re-runs reuse them and nothing collides.
        digest = hashlib.sha1(path.read_bytes()).hexdigest()[:16]
        name = f"{digest}.png"
        with open(path, "rb") as f:
            r = requests.post(f"{self.url}/upload/image",
                              files={"image": (name, f, "image/png")},
                              data={"subfolder": "launcher_bg", "type": "input", "overwrite": "true"},
                              timeout=60)
        r.raise_for_status()
        j = r.json()
        return f"{j['subfolder']}/{j['name']}" if j.get("subfolder") else j["name"]

    def run(self, workflow):
        """Queue a workflow and return the bytes of the first saved image."""
        r = requests.post(f"{self.url}/prompt",
                          json={"prompt": workflow, "client_id": self.client_id}, timeout=30)
        if r.status_code != 200:
            raise RuntimeError(f"ComfyUI rejected the workflow: {r.text[:1000]}")
        pid = r.json()["prompt_id"]
        while True:
            h = requests.get(f"{self.url}/history/{pid}", timeout=30).json()
            if pid in h:
                break
            time.sleep(0.5)
        status = h[pid]["status"]
        if status.get("status_str") != "success":
            raise RuntimeError(f"ComfyUI job failed: {json.dumps(status.get('messages'))[-1500:]}")
        for out in h[pid]["outputs"].values():
            for im in out.get("images", []):
                v = requests.get(f"{self.url}/view", params=im, timeout=120)
                v.raise_for_status()
                return v.content
        raise RuntimeError("ComfyUI job produced no image")


def load_workflow(name):
    return json.loads((WORKFLOWS / f"{name}.api.json").read_text())


# ---------------------------------------------------------------- config

class Config:
    def __init__(self, path):
        self.raw = json.loads(path.read_text())
        c = self.raw
        self.style = c["style"]
        self.focals = c["focal_elements"]
        self.seasons = c["seasons"]
        self.times = c["times"]
        self.conditions = c["conditions"]
        self.holidays = c["holidays"]

    def skipped(self, season, time_, condition):
        for rule in self.raw["skip"]:
            if all(rule.get(k) in (None, v) for k, v in
                   (("season", season), ("time", time_), ("condition", condition))):
                return True
        return False

    def seed(self, stage, key_path):
        return self.raw["seed_overrides"].get(f"{stage}/{key_path}", self.raw["seeds"][stage])

    def keep(self, noun):
        return f"{self.raw['keep']} Keep the {noun} in the same place, at the same small size."


# ---------------------------------------------------------------- jobs

@dataclass
class Job:
    key: tuple                 # e.g. ("weather", "windmill", "autumn", "day", "fog")
                               # or ("holiday_weather", "windmill", "halloween", "day", "rainy")
    kind: str                  # edit | copy | final
    out: Path
    src: tuple | None          # key of the input job (None = chosen base)
    prompt: str = ""
    seed: int = 0
    label: str = field(default="")


def key_path(key):
    return "/".join(key[1:])


def build_job(cfg, key):
    stage, *rest = key
    if stage == "focal":
        (f,) = rest
        fe = cfg.focals[f]
        return Job(key, "edit", WORK / "focal" / f"{f}.png", None,
                   f"{cfg.style} {fe['add']} {cfg.raw['keep']}", cfg.seed("focal", f))
    if stage == "final":
        src = tuple(rest)
        if src[0] == "weather":
            _, f, s, t, c = src
            out = FINAL / f / s / t / f"{c}.webp"
        elif src[0] == "holiday_weather":
            _, f, h, t, c = src
            out = FINAL / "holidays" / f / h / t / f"{c}.webp"
        else:
            _, f, h, t = src
            out = FINAL / "holidays" / f / h / f"{t}.webp"
        return Job(key, "final", out, tuple(src))
    noun = cfg.focals[rest[0]]["noun"]
    if stage == "season":
        f, s = rest
        return Job(key, "edit", WORK / "season" / f / f"{s}.png", ("focal", f),
                   f"{cfg.style} {cfg.seasons[s].format(noun=noun)} {cfg.keep(noun)}", cfg.seed("season", key_path(key)))
    if stage == "time":
        f, s, t = rest
        out = WORK / "time" / f / s / f"{t}.png"
        if cfg.times[t] is None:   # e.g. day: the season image already is daytime
            return Job(key, "copy", out, ("season", f, s))
        return Job(key, "edit", out, ("season", f, s),
                   f"{cfg.style} {cfg.times[t].format(noun=noun)} {cfg.keep(noun)}",
                   cfg.seed("time", key_path(key)))
    if stage == "weather":
        f, s, t, c = rest
        # Time first so it overrides the style's daytime palette, then the season's colors.
        hills = cfg.raw["hill_colors"][s] + (", darkened by moonlight" if t == "night" else "")
        prompt = (f"{cfg.style} {cfg.raw['time_keep'][t].format(noun=noun)} {cfg.conditions[c]} "
                  f"Keep the hills {hills}. {cfg.keep(noun)}")
        return Job(key, "edit", WORK / "weather" / f / s / t / f"{c}.png", ("time", f, s, t),
                   prompt, cfg.seed("weather", key_path(key)))
    if stage == "holiday":
        f, h, t = rest
        hol = cfg.holidays[h]
        hills = cfg.raw["hill_colors"][hol["season"]] + (", darkened by moonlight" if t == "night" else "")
        fe = cfg.focals[f]
        # A focal element can replace a holiday's prompt (the farmhouse gets a lit
        # evergreen for Christmas: lights on the house itself redrew the house).
        template = fe.get("holiday_prompts", {}).get(h, hol["prompt"])
        # Where decorations hang and sit, e.g. "the windmill's tower" / "the farmhouse's front step".
        decor = template.format(noun=noun, hang_on=fe.get("hang_on", f"the {noun}"),
                                foot=fe.get("foot", f"the foot of the {noun}"),
                                lights_on=fe.get("lights_on", f"the {noun}"))
        prompt = (f"{cfg.style} {cfg.raw['time_keep'][t].format(noun=noun)} {decor} "
                  f"Keep the hills {hills}. {cfg.keep(noun)}")
        return Job(key, "edit", WORK / "holiday" / f / h / f"{t}.png",
                   ("weather", f, hol["season"], t, "clear"), prompt, cfg.seed("holiday", key_path(key)))
    if stage == "holiday_weather":
        # The weather edit applied to the finished holiday image, so its
        # decorations are the same in every condition.
        f, h, t, c = rest
        hol = cfg.holidays[h]
        hills = cfg.raw["hill_colors"][hol["season"]] + (", darkened by moonlight" if t == "night" else "")
        # Some conditions need a gentler wording so they don't bury the decorations.
        weather = cfg.raw.get("holiday_conditions", {}).get(c, cfg.conditions[c])
        # Some decorations need their own reminder, e.g. Christmas lights fade out at night otherwise.
        keep = cfg.raw["holiday_keep"] + (f" {hol['keep']}" if hol.get("keep") else "")
        prompt = (f"{cfg.style} {cfg.raw['time_keep'][t].format(noun=noun)} {weather} "
                  f"Keep the hills {hills}. {keep} {cfg.keep(noun)}")
        return Job(key, "edit", WORK / "holiday_weather" / f / h / t / f"{c}.png",
                   ("holiday", f, h, t), prompt, cfg.seed("holiday_weather", key_path(key)))
    raise ValueError(key)


def targets(cfg, stages, sel):
    """All job keys in the selected stages that match the filters, in chain order."""
    keys = []
    focals, seasons, times = sel["focal"], sel["season"], sel["time"]
    conds, hols = sel["condition"], sel["holiday"]
    weather_keys = [("weather", f, s, t, c) for f in focals for s in seasons for t in times
                    for c in conds if not cfg.skipped(s, t, c)]
    holiday_keys = [("holiday", f, h, t) for f in focals for h in hols for t in times
                    if cfg.holidays[h]["season"] in seasons]
    # Clear is the holiday image itself.
    holiday_weather_keys = [("holiday_weather", f, h, t, c) for _, f, h, t in holiday_keys
                            for c in conds if c != "clear"
                            and not cfg.skipped(cfg.holidays[h]["season"], t, c)]
    for stage in stages:
        if stage == "focal":
            keys += [("focal", f) for f in focals]
        elif stage == "season":
            keys += [("season", f, s) for f in focals for s in seasons]
        elif stage == "time":
            keys += [("time", f, s, t) for f in focals for s in seasons for t in times]
        elif stage == "weather":
            keys += weather_keys
        elif stage == "holiday":
            keys += holiday_keys
        elif stage == "holiday_weather":
            keys += holiday_weather_keys
        elif stage == "final":
            if "weather" in sel["final_of"]:
                keys += [("final", *k) for k in weather_keys]
            if "holiday" in sel["final_of"]:
                keys += [("final", *k) for k in holiday_keys]
            if "holiday_weather" in sel["final_of"]:
                keys += [("final", *k) for k in holiday_weather_keys]
    return keys


def plan(cfg, target_keys, force):
    """Order the jobs that need to run, pulling in missing upstream images."""
    todo, seen = [], set()

    def visit(key):
        if key in seen:
            return
        seen.add(key)
        job = build_job(cfg, key)
        needed = (force and key in forced) or not job.out.exists()
        if not needed:
            return
        if job.src is not None:
            visit(job.src)
        todo.append(job)

    forced = set(target_keys)
    for k in target_keys:
        visit(k)
    return todo


# ---------------------------------------------------------------- execution

def write_atomic(path, data_or_image, **save_kwargs):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    if isinstance(data_or_image, bytes):
        tmp.write_bytes(data_or_image)
    else:
        data_or_image.save(tmp, **save_kwargs)
    tmp.replace(path)


def run_edit(comfy, cfg, job, src_path):
    wf = load_workflow("flux2_klein_4b_edit")
    wf["76"]["inputs"]["image"] = comfy.upload(src_path)
    wf["74"]["inputs"]["text"] = job.prompt
    wf["73"]["inputs"]["noise_seed"] = job.seed
    write_atomic(job.out, comfy.run(wf))


def run_final(comfy, cfg, job, src_path):
    wf = load_workflow("upscale_4k")
    wf["1"]["inputs"]["image"] = comfy.upload(src_path)
    wf["4"]["inputs"]["width"] = cfg.raw["final_width"]
    wf["4"]["inputs"]["height"] = cfg.raw["final_height"]
    png = WORK / ".upscale.png"
    write_atomic(png, comfy.run(wf))
    img = Image.open(png).convert("RGB")
    grain = cfg.raw.get("final_grain", 0)
    if grain:
        # The upscaler smooths away the model's grain; add a fine, deterministic
        # grain back at full resolution (it also dithers away gradient banding).
        rng = np.random.default_rng(int(hashlib.sha1(key_path(job.key).encode()).hexdigest()[:8], 16))
        a = np.asarray(img, dtype=np.float32)
        a += rng.normal(0, grain * 255, a.shape[:2])[..., None]
        img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    write_atomic(job.out, img, format="WEBP", quality=cfg.raw["webp_quality"], method=6)
    png.unlink(missing_ok=True)


def src_path_of(cfg, job):
    return CHOSEN_BASE if job.src is None else build_job(cfg, job.src).out


def fmt_secs(s):
    s = int(s)
    return f"{s // 3600}h{s % 3600 // 60:02d}m" if s >= 3600 else f"{s // 60}m{s % 60:02d}s"


def execute(comfy, cfg, jobs):
    timings = {k: [] for k in DEFAULT_SECONDS}

    def est(kind):
        t = timings[kind]
        return sum(t) / len(t) if t else DEFAULT_SECONDS[kind]

    n = len(jobs)
    for i, job in enumerate(jobs, 1):
        remaining = sum(est(j.kind) for j in jobs[i - 1:])
        name = f"{job.key[0]} {key_path(job.key)}"
        print(f"[{i}/{n}] {name}  (ETA {fmt_secs(remaining)})", flush=True)
        src = src_path_of(cfg, job)
        if not src.exists():
            raise RuntimeError(f"missing input {src}")
        t0 = time.time()
        if job.kind == "copy":
            job.out.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, job.out)
        elif job.kind == "edit":
            run_edit(comfy, cfg, job, src)
        elif job.kind == "final":
            run_final(comfy, cfg, job, src)
        timings[job.kind].append(time.time() - t0)


# ---------------------------------------------------------------- base

def base_candidate_path(seed):
    return WORK / "base" / "candidates" / f"seed_{seed}.png"


def run_base(comfy, cfg, force):
    b = cfg.raw["base"]
    seeds = b["candidate_seeds"]
    todo = [s for s in seeds if force or not base_candidate_path(s).exists()]
    for i, seed in enumerate(todo, 1):
        print(f"[{i}/{len(todo)}] base candidate seed {seed}", flush=True)
        wf = load_workflow("flux2_klein_4b_t2i")
        wf["74"]["inputs"]["text"] = f"{cfg.style} {b['prompt']}"
        wf["67"]["inputs"]["text"] = b["negative"]
        wf["73"]["inputs"]["noise_seed"] = seed
        for node in ("62", "66"):
            wf[node]["inputs"]["width"] = cfg.raw["width"]
            wf[node]["inputs"]["height"] = cfg.raw["height"]
        write_atomic(base_candidate_path(seed), comfy.run(wf))
    sheet = REVIEW / "base_candidates.jpg"
    contact_sheet([(f"seed {s}", base_candidate_path(s)) for s in seeds], 4, sheet)
    print(f"\nBase candidates: {base_candidate_path(0).parent}\nContact sheet:   {sheet}\n"
          "STOP: pick one, then run  scripts/generate_backgrounds.py --choose-base <seed>")


def choose_base(cfg, seed, force):
    src = base_candidate_path(seed)
    if not src.exists():
        sys.exit(f"No candidate for seed {seed} ({src}). Run --stage base first.")
    if CHOSEN_BASE.exists() and not force:
        sys.exit(f"{CHOSEN_BASE} already exists. Replacing it changes every image built on it; "
                 "pass --force if you really mean it (then regenerate with --force).")
    shutil.copyfile(src, CHOSEN_BASE)
    b = cfg.raw["base"]
    CHOSEN_META.write_text(json.dumps({
        "seed": seed, "prompt": f"{cfg.style} {b['prompt']}", "negative": b["negative"],
        "workflow": "flux2_klein_4b_t2i", "chosen": time.strftime("%Y-%m-%d"),
    }, indent=2) + "\n")
    print(f"Saved {CHOSEN_BASE.relative_to(REPO)} (seed {seed}).")


# ---------------------------------------------------------------- review + manifest

def contact_sheet(items, cols, out, thumb=(448, 256)):
    items = [(label, p) for label, p in items if p and p.exists()]
    if not items:
        return
    rows = (len(items) + cols - 1) // cols
    pad, lh = 8, 20
    sheet = Image.new("RGB", (cols * (thumb[0] + pad) + pad, rows * (thumb[1] + lh + pad) + pad), (24, 24, 24))
    d = ImageDraw.Draw(sheet)
    for i, (label, p) in enumerate(items):
        x = pad + (i % cols) * (thumb[0] + pad)
        y = pad + (i // cols) * (thumb[1] + lh + pad)
        im = Image.open(p).convert("RGB")
        im.thumbnail(thumb)
        sheet.paste(im, (x, y + lh))
        d.text((x, y + 3), label, fill=(230, 230, 230))
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out, quality=88)


def preview_sheet(cfg, f):
    s = "autumn"
    chain = [("chosen base", CHOSEN_BASE), ("focal", WORK / "focal" / f"{f}.png"),
             (f"season {s}", WORK / "season" / f / f"{s}.png")]
    chain += [(f"time {t}", WORK / "time" / f / s / f"{t}.png") for t in cfg.times]
    items = chain + [("", None)] * (-len(chain) % 5)
    for t in cfg.times:
        items += [(f"{t} / {c}", FINAL / f / s / t / f"{c}.webp") for c in cfg.conditions]
    out = REVIEW / f"preview_{f}_autumn.jpg"
    # Blank slots keep each time-of-day on its own rows.
    contact_sheet([(l, p) for l, p in items if p], 5, out)
    return out


def holiday_sheet(cfg, f, h):
    times = list(cfg.times)
    items = []
    for t in times:
        items += [(f"{h} {t} / {c}", FINAL / "holidays" / f / h /
                   (f"{t}.webp" if c == "clear" else f"{t}/{c}.webp")) for c in cfg.conditions]
    out = REVIEW / f"holiday_{f}_{h}.jpg"
    contact_sheet(items, len(cfg.conditions), out)
    return out


def write_manifest(cfg):
    FINAL.mkdir(parents=True, exist_ok=True)
    files = sorted(str(p.relative_to(FINAL)) for p in FINAL.rglob("*.webp"))
    focals = sorted({p.split("/")[0] for p in files if not p.startswith("holidays/")}
                    | {p.split("/")[1] for p in files if p.startswith("holidays/")})
    manifest = {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "focal_elements": focals,
        "seasons": list(cfg.seasons),
        "times": list(cfg.times),
        "conditions": list(cfg.conditions),
        "holidays": {h: v["season"] for h, v in cfg.holidays.items()},
        "skip": cfg.raw["skip"],
        "paths": {
            "weather": "<focal>/<season>/<time>/<condition>.webp",
            "holiday": "holidays/<focal>/<holiday>/<time>.webp",
            "holiday_weather": "holidays/<focal>/<holiday>/<time>/<condition>.webp",
        },
        "files": files,
    }
    (FINAL / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"manifest.json: {len(files)} files, focal elements: {', '.join(focals) or 'none'}")


# ---------------------------------------------------------------- CLI

def csv_arg(valid, what):
    def parse(s):
        items = [x.strip() for x in s.split(",") if x.strip()]
        bad = [x for x in items if x not in valid]
        if bad:
            raise argparse.ArgumentTypeError(f"unknown {what}: {', '.join(bad)} (valid: {', '.join(valid)})")
        return items
    return parse


def main():
    cfg = Config(CFG_DIR / "config.json")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--stage", type=csv_arg(["base"] + STAGES, "stage"),
                    help="only these stages (comma-separated): base, " + ", ".join(STAGES))
    ap.add_argument("--from", dest="from_stage", choices=STAGES,
                    help="this stage and every later one (use with --force to redo a chain)")
    ap.add_argument("--focal", type=csv_arg(list(cfg.focals), "focal"))
    ap.add_argument("--season", type=csv_arg(list(cfg.seasons), "season"))
    ap.add_argument("--time", type=csv_arg(list(cfg.times), "time"))
    ap.add_argument("--condition", type=csv_arg(list(cfg.conditions), "condition"))
    ap.add_argument("--holiday", type=csv_arg(list(cfg.holidays), "holiday"))
    ap.add_argument("--force", action="store_true", help="regenerate selected images even if they exist")
    ap.add_argument("--preview", action="store_true",
                    help="run one chain (windmill, or --focal X) / autumn / day+night / all conditions, "
                         "then stop for review")
    ap.add_argument("--choose-base", type=int, metavar="SEED", help="keep this base candidate")
    ap.add_argument("--dry-run", action="store_true", help="list what would run")
    ap.add_argument("--manifest-only", action="store_true", help="just rebuild manifest.json")
    ap.add_argument("--url", default=cfg.raw["comfyui_url"], help="ComfyUI URL")
    a = ap.parse_args()

    if a.manifest_only:
        return write_manifest(cfg)
    if a.choose_base is not None:
        return choose_base(cfg, a.choose_base, a.force)

    comfy = Comfy(a.url)
    if a.stage and "base" in a.stage:
        if a.dry_run:
            print("would generate base candidates:", cfg.raw["base"]["candidate_seeds"])
            return
        comfy.check()
        return run_base(comfy, cfg, a.force)

    if not CHOSEN_BASE.exists():
        sys.exit("No chosen base yet. Run --stage base, then --choose-base <seed>.")

    sel = {
        "focal": a.focal or list(cfg.focals),
        "season": a.season or list(cfg.seasons),
        "time": a.time or list(cfg.times),
        "condition": a.condition or list(cfg.conditions),
        "holiday": a.holiday or list(cfg.holidays),
        "final_of": {"weather", "holiday", "holiday_weather"},
    }
    if a.stage:
        stages = a.stage
    elif a.from_stage:
        stages = STAGES[STAGES.index(a.from_stage):]
    elif a.holiday or a.condition:
        # --holiday / --condition alone target just those images (and their 4K finals);
        # missing upstream images are still generated, but never forced.
        stages = ["weather", "holiday", "holiday_weather", "final"]
    else:
        stages = STAGES
    if a.holiday and a.condition:
        stages = [s for s in stages if s in ("holiday_weather", "final")]
        sel["final_of"] = {"holiday_weather"}
    elif a.holiday:
        stages = [s for s in stages if s != "weather"]
        sel["final_of"] = {"holiday", "holiday_weather"}
    elif a.condition:
        stages = [s for s in stages if s not in ("holiday", "holiday_weather")]
        sel["final_of"] = {"weather"}

    if a.preview:
        sel.update(PREVIEW, condition=list(cfg.conditions), final_of={"weather"})
        if a.focal:
            sel["focal"] = a.focal[:1]
        stages = [s for s in STAGES if s not in ("holiday", "holiday_weather")]

    jobs = plan(cfg, targets(cfg, stages, sel), a.force)
    kinds = {k: sum(j.kind == k for j in jobs) for k in ("edit", "copy", "final")}
    print(f"{len(jobs)} jobs: {kinds['edit']} edits, {kinds['final']} finals (4K WebP), {kinds['copy']} copies")
    if a.dry_run:
        for j in jobs:
            print(f"  {j.kind:5} {j.key[0]:8} {key_path(j.key)} -> {j.out.relative_to(REPO)}")
        return

    try:
        if jobs:
            comfy.check()
            execute(comfy, cfg, jobs)
    finally:
        write_manifest(cfg)

    if a.holiday and not a.dry_run:
        for h in sel["holiday"]:
            for f in sel["focal"]:
                print(f"Contact sheet: {holiday_sheet(cfg, f, h)}")
    if a.preview:
        sheet = preview_sheet(cfg, sel["focal"][0])
        print(f"\nPreview done. Contact sheet: {sheet}\n"
              f"Finals: {FINAL / sel['focal'][0] / 'autumn'}\n"
              "STOP: check for drift between images. If it looks right, run the full batch:\n"
              "  scripts/generate_backgrounds.py")


if __name__ == "__main__":
    main()
