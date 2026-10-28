# Xiaomi 13 Pro — Camera Reloaded

**The Xiaomi 13 Pro has one of the best phone camera sensors of its generation (1-inch class Sony IMX989). The software around it is where it falls short:
photos that got worse after updates, a preview that pumps and flickers when the light changes, a pipeline you cannot inspect or fix.**
This project is an attempt to take that back: understand exactly what the stock camera software does, find what it does badly, and build a camera app that does it better — and to document
every step publicly so other owners with the same problems can use it.

## Purpose
1. **A better camera app for the Xiaomi 13 Pro (nuwa).** Start from the camera app of an early, well-behaved software version (reverse engineered from firmware you own),
   re-package it with its **own signature and package name** so it installs *next to* the stock Camera instead of replacing it, keep the vendor libraries it needs,
   and replace or fix the parts that are weak. Only public Android APIs in the code we add (no hidden-API hacks).
2. **Find and fix the real causes of the bad behaviour, with evidence.** Every claim in this repo comes from a measurement (per-frame Camera2 results, screen-recording luma,
   A/B against the stock app, a plain Camera2 control app). Ideas that failed are documented as carefully as ideas that worked.
3. **Help other people with the same problem.** If your Xiaomi/HyperOS camera preview flickers, brightness jumps in steps instead of ramping, or photo quality dropped after an update,
   the methods, scripts and findings here are meant to save you weeks. Measurements from other devices are very welcome.
4. **Improve quality beyond fixing bugs**, where the hardware allows it: full-resolution captures (the sensor delivers 12.5 MP binned through the public API; 50 MP is a vendor-only path),
   RAW/DNG output, multi-frame merging and a smoother, faster UI — each one judged by A/B numbers, never by promises.

## What it is not
- Not a Xiaomi release, not affiliated with Xiaomi, and **no Xiaomi binaries, firmware or keys are distributed here** (see [NOTICE](NOTICE.md)). You extract the app from firmware you are entitled to use.
- Not "100× better than stock": that cannot be measured. The goal is measurable improvements on a defined list of metrics.
- Not finished. Current state is below.

## Status (honest)
- The OEM-camera port installs, opens the main camera and ultrawide/tele, previews, focuses smoothly and saves photos.
- **Preview brightness flicker is not solved yet** — it is the first problem being closed; the investigation so far is in `docs/FINDINGS.md`.
- Photo resolution/quality work (full 12.5 MP, RAW, merging) has not started; it follows the flicker fix.

## Who this is for
- You see the camera preview "pump" or jump in brightness (not a smooth ramp) on a recent Xiaomi/HyperOS phone, especially in
  third-party camera apps or ports, while the stock camera looks stable.
- You want a **repeatable, objective** way to measure preview flicker instead of "it looks bad".
- You are porting/side-loading an OEM camera app and hit the usual walls (hidden APIs, vendor operation modes, session keys).

## What is in this repo
| Path | What |
|---|---|
| `docs/FINDINGS.md` | Hypotheses tried, results, numbers (what worked, what did **not**) |
| `docs/METHOD.md` | How to measure preview flicker objectively (screen-record luma, per-frame Camera2 results, EV step response) |
| `tools/` | Measurement scripts (ADB + ffmpeg + Python), keep-awake helper |
| `probe/` | Tiny plain-Camera2 probe app source (logs exposure/ISO/AE state/luma per frame) – the "control" experiment |
| `src/` | App-owned compatibility classes used by the port (public Android APIs only), with host tests |
| `data/` | Raw summaries of the step-response measurements |

**Not included, on purpose:** Xiaomi APKs, firmware, decoded/smali code, native libraries, signing keys. See [NOTICE](NOTICE.md).

## Short version of the findings
1. The sensor is **not** the cause. Per-frame `CaptureResult`s show constant exposure/ISO/AE state during the flashes.
2. A plain Camera2 app on the same phone and scene shows **no** jumps; the stock Xiaomi camera shows none either.
3. The jumps are already present on the camera `SurfaceTexture` (OES) before any app-side GL rendering, so the app renderer is not the origin.
4. Request tags, HDR/LLS/ASD flags, Xiaomi vendor tags (even blocking *all* `xiaomi.*` request keys) did not remove the flashes.
5. The way **AE reacts to brightness changes** differs: with default settings, a forced EV step is applied in a single frame (≈99 % of the change in one
   frame), while some settings (explicit 50/60 Hz antibanding, a different operation mode) ramp it over ~13–45 frames. See `docs/FINDINGS.md` for numbers and caveats (small *n*).

## Quick start (measure your own phone)
```bash
export ADB_SERIAL=<phone-ip>:<port>      # adb over wifi or USB serial
# 8 s screen recording of the preview, luma of the centre of the frame, count of frame-to-frame jumps
bash tools/taglab.sh "baseline" "" 3
```
Requires `adb`, `ffmpeg`, `python3`. Do **not** treat a single run as evidence: the metric is noisy (see METHOD).

## Contributing / reporting
Issues and PRs are welcome (measurements on other devices especially). The `main` branch is protected: changes go through pull requests.
Please never post firmware, APKs or keys.

## Licence
Original code and documents: MIT (see [LICENSE](LICENSE)). Xiaomi software and trademarks belong to their owners and are not part of this repo.
