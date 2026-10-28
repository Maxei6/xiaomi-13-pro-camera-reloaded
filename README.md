# Xiaomi 13 Pro camera: brightness flicker & OEM-camera port research

Field notes, measurement tools and test code from an attempt to run a re-signed, re-packaged build of the
Xiaomi camera app on a **Xiaomi 13 Pro (nuwa), HyperOS 3 / Android 16**, next to the stock app — and to find out
why the preview **flickers / changes brightness in steps instead of smoothly** when the scene light changes.

> **Status: work in progress, not solved.** Everything below is what was *measured*; unproven ideas are labelled as such.
> Updated as the investigation continues. If you have the same problem, the methods and numbers here should save you time.

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
