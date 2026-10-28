# Launcher backgrounds

Flat, layered Kansas Flint Hills backgrounds for the launcher, one per
season × day/night × weather condition, plus holidays. They're generated
locally with FLUX.2 Klein 4B in ComfyUI (Docker, RTX 3080), upscaled to 4K
and shipped to the SHIELDs inside the launcher's APK.

## ComfyUI

Runs from [`comfyui/docker-compose.yml`](../../comfyui/docker-compose.yml)
(image `mmartial/comfyui-nvidia-docker:ubuntu24_cuda12.8-20261018`).

```bash
cd comfyui
docker compose up -d          # start (also starts on boot: restart: unless-stopped)
docker compose logs -f        # ready when you see "To see the GUI go to"
docker compose down           # stop
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8188/system_stats   # 200 = up
```

UI and API: `http://<host>:8188`. It's open to the LAN with no auth.

Things worth knowing:

- **Startup upgrades are off** (`DISABLE_UPGRADES=true`), so the node graph
  doesn't change under the pipeline. To update ComfyUI and its packages, run
  `DISABLE_UPGRADES=false docker compose up -d --force-recreate` once.
- **The compose file sets `LD_LIBRARY_PATH`** so torch loads the host's
  `libcuda` instead of the image's bundled `cuda-compat` one. Without it,
  ComfyUI crashes with `Error 804: forward compatibility was attempted on non
  supported HW`. That happens whenever the image's compat library is newer
  than the host driver, which GeForce cards can't use. Keep it if you change
  the image tag.
- First start takes several minutes: it installs torch and ComfyUI into
  `comfyui/run/venv`.

### Where things live

| Path | What |
| --- | --- |
| `comfyui/models/` | Model files (mounted at `/basedir/models`) |
| `comfyui/input/`, `comfyui/output/` | ComfyUI's own input/output. The pipeline uploads to `input/launcher_bg/`; ComfyUI also keeps a copy of every result in `output/launcher_bg/`, which is safe to delete. |
| `comfyui/custom_nodes/`, `comfyui/user/` | Custom nodes; settings and UI-saved workflows (`user/default/workflows/`) |
| `comfyui/run/` | ComfyUI source and Python venv (managed by the image) |
| `scripts/backgrounds/config.json` | Style string, prompts, seeds, skip list, holidays |
| `scripts/backgrounds/workflows/` | API-format workflows the script submits |
| `scripts/backgrounds/chosen_base.png` | The chosen base landscape (+ `chosen_base.json` with its seed). Committed: everything is built from it. |
| `output/work/` | Intermediate images for every stage (1344×768 PNG) |
| `output/backgrounds/` | Finals (3840×2160 WebP q90) and `manifest.json`; bundled into the APK by `./build.sh` |
| `output/review/` | Contact sheets for picking the base and checking the preview |

`comfyui/*` (except the compose file) is gitignored, and so is `output/`,
except for `output/backgrounds/`: the finished set is committed because the APK
bundles it. Commit it again after regenerating; a full regeneration adds about
357 MB to the repo's history, so prefer rerunning single images.

### Models

Filenames and folders come from ComfyUI's official templates
*FLUX.2 [Klein] 4B: Text to Image* (`image_flux2_klein_text_to_image`) and
*FLUX.2 [Klein] 4B Distilled: Image Edit* (`image_flux2_klein_image_edit_4b_distilled`).
All of them are Apache 2.0, except the upscaler, which is BSD-3.

| File | Folder | Used by |
| --- | --- | --- |
| `flux-2-klein-base-4b.safetensors` | `diffusion_models` | text-to-image (base, 20 steps, CFG 5) |
| `flux-2-klein-4b-fp8.safetensors` | `diffusion_models` | image edit (distilled, 4 steps, CFG 1) |
| `qwen_3_4b.safetensors` | `text_encoders` | both |
| `flux2-vae.safetensors` | `vae` | both |
| `RealESRGAN_x4plus_anime_6B.pth` | `upscale_models` | 4K upscale |

**Why `RealESRGAN_x4plus_anime_6B` for upscaling:** it was trained on
flat-shaded artwork. It keeps large flat color areas and soft gradients clean,
and it keeps layer edges crisp without inventing texture. Photo-oriented
upscalers (UltraSharp, x4plus) add fake grass/noise detail and ringing at hill
edges. It also smooths away grain, so the script adds a fine, seeded film
grain back at 4K (`final_grain` in the config; `0` turns it off). The grain
also breaks up banding in the big sky gradients after WebP compression. It's
an 18 MB model, so it's fast.

## Art direction

Everything comes from one shared `style` string in `config.json`, prefixed to
every prompt. Every edit also ends with the `keep` string, which tells the
model to keep the composition and the left side calm.

- **Scene:** Kansas Flint Hills: treeless, gently rolling tallgrass hills as flat stacked layers, big sky.
- **Style:** flat layered landscape, soft gradients, subtle film grain, minimal, cozy and calm, no outlines, no text.
- **Palette:** warm, muted earth tones: tallgrass gold, sage, rust, dusty blue sky. The seasons shift the hill colors.
- **Composition (16:9):** horizon at about 50% height. The left 60% stays simple and low-contrast for the UI tiles and text; all visual interest goes in the right third. The focal element sits on a ridge at about 75–85% across, just below the horizon, no more than 10% of the frame width.
- **Weather** is carried mostly by the sky. **Night:** dark sky, moonlight, focal element in silhouette.

## How the images are built

Each image is an edit of the one before, so the whole set is the same scene:

```
chosen base ─▶ focal (windmill) ─▶ season ×4 ─▶ time: day | night ─▶ condition ×9
                                                       └─▶ holiday (from <holiday's season>/<time>/clear)
                                                              └─▶ holiday weather ×8 (every condition but clear)
```

- **day** is a copy of the season image (`"day": null` in `times`); that's already daytime, and copying avoids one generation of drift. **night** is an edit.
- **Skipped:** `summer/snowy`, day and night (`skip` in the config).
- **Holidays** and the season they start from: valentines, christmas and new_years → winter; st_patricks and easter → spring; july_4th → summer; halloween and thanksgiving → autumn. Each one is made for day and night, and decorations stay small and in the right third.
- **Holiday weather** applies the same condition edit to the finished holiday image (plus `holiday_keep`, so the decorations stay put), so a rainy Halloween keeps the same jack-o'-lantern. A holiday can add its own `keep` line to that; Christmas does, because the tiny lights otherwise faded out in the night weather. Clear is the holiday image itself. The launcher shows the holiday in today's weather, falling back to the clear holiday image if that one is missing.
- `holiday_conditions` replaces a condition's wording for holiday images only. The thunderstorm one keeps its clouds along the top edge; the normal one fills the sky and buried the hearts and fireworks.
- Seeds are fixed per stage in `seeds`. Use `seed_overrides` to reroll a single image, e.g. `"weather/windmill/autumn/night/fog": 999`.

Outputs:

```
output/backgrounds/<focal>/<season>/<day|night>/<condition>.webp
output/backgrounds/holidays/<focal>/<holiday>/<day|night>.webp              # clear
output/backgrounds/holidays/<focal>/<holiday>/<day|night>/<condition>.webp  # other weather
output/backgrounds/manifest.json   # rebuilt after every run
```

`manifest.json` lists the focal elements that actually have files, the
seasons, times, conditions, holidays (with their season), the skip list, and
every `.webp` that exists.

## Running it

Each focal element's full set is 221 edits and 212 4K finals (70 weather + 16 holiday + 126 holiday weather).

```bash
# 1. Base candidates (one per seed in base.candidate_seeds), then stop.
scripts/generate_backgrounds.py --stage base
#    Look at output/review/base_candidates.jpg and pick one:
scripts/generate_backgrounds.py --choose-base 404

# 2. Preview one full chain (windmill / autumn / day+night / all 9 conditions), then stop.
scripts/generate_backgrounds.py --preview
#    Check output/review/preview_windmill_autumn.jpg for drift.

# 3. Everything else (already-finished images are skipped).
scripts/generate_backgrounds.py
```

It's resumable: existing files are skipped, and an interrupted run just
continues. Writes are atomic, so a half-written file never counts as done.
`--dry-run` lists what would run.

### Regenerating parts

Filters take comma-separated values. `--force` redoes the selected images even
if they exist. Missing upstream images are always built first, but they're
never forced.

```bash
# One image
scripts/generate_backgrounds.py --season autumn --time night --condition fog --force

# One holiday (both times, every condition); writes output/review/holiday_<focal>_<holiday>.jpg
scripts/generate_backgrounds.py --holiday christmas --force

# One holiday in one weather
scripts/generate_backgrounds.py --holiday christmas --condition snowy --force

# Re-do winter from the season edit down (season, night, conditions, winter holidays, finals)
scripts/generate_backgrounds.py --from season --season winter --force

# Only one stage, e.g. re-upscale every final after changing final_grain
scripts/generate_backgrounds.py --stage final --force

# Rebuild the manifest only
scripts/generate_backgrounds.py --manifest-only
```

If you change a prompt in `config.json`, nothing regenerates on its own. Run
the affected stage with `--force` (and `--from` to carry it down the chain).

To change the base itself, use `--choose-base <seed> --force`, then
`scripts/generate_backgrounds.py --from focal --force`. That redoes everything.

## Adding a focal element (farmhouse, lone_tree, …)

Add an entry to `focal_elements` in `config.json`:

```json
"farmhouse": {
  "noun": "farmhouse",
  "add": "Add a small white farmhouse with a red roof standing on the ridge at about 80% across the image, just below the horizon. It is small in the frame, no wider than a tenth of the image width, drawn in the same flat style."
}
```

`noun` fills the `{noun}` slots in the night, weather and holiday prompts
("the farmhouse becomes a dark silhouette"). The optional `hang_on` and `foot`
fill the holiday prompts' `{hang_on}` and `{foot}`: where decorations hang
("a heart on the farmhouse's front door") and where things sit on the ground
("a jack-o'-lantern right at the farmhouse's front step"). They default to
"the farmhouse" and "the foot of the farmhouse". `lights_on` fills the
Christmas prompt's `{lights_on}` (where the string lights go, e.g. "wrapped
around the branches of its canopy"; default "the farmhouse").

`holiday_prompts` replaces a holiday's prompt for that focal element only. The
farmhouse uses it twice, because decorations on or at the house made the model
redraw the house: for Christmas it gets one lit evergreen in the yard instead of
lights on the roof, and for Halloween the jack-o'-lantern sits in the grass
beside the house (asking for it "at the front step" grew a porch). Both
prompts say not to change the farmhouse. Then:

```bash
scripts/generate_backgrounds.py --preview --focal farmhouse   # autumn chain, then stop for review
scripts/generate_backgrounds.py --focal farmhouse             # the rest
```

It starts from the same `chosen_base.png`. The windmill files already exist,
so they're left alone. The manifest picks up the new focal element
automatically.

## Getting them onto the device

They ship in the launcher's APK. `./build.sh` copies `output/backgrounds/`
(the WebPs and `manifest.json`) into `assets/backgrounds/`, and
`./build.sh install` puts them on every SHIELD. After generating new images or
a new focal element, rebuild and install. Each focal element's set is about
357 MB (212 files, ~1.7 MB each). With the windmill, farmhouse and lone tree
that's 636 files and about 1.1 GB, so the APK is about 1.1 GB too.
