# Findings log (Xiaomi 13 Pro, HyperOS 3 / Android 16)

Device: Xiaomi 13 Pro (nuwa), IMX989 main sensor (HAL camera id 2), Qualcomm CamX HAL. Stock camera: 6.4.x. Ported app: camera 4.5.x re-signed with a
different package name so both can coexist.

**Metric reminder:** "jumps" = frame-to-frame luma change > 1.5 in an 8 s screen recording; noisy (see METHOD.md). n = number of runs.

## Symptoms
- Preview brightness changes **in steps** instead of a gradual ramp when scene light changes.
- With a static scene: occasional single-frame +2…+4 luma flashes, spatially uneven (top region brighter by 6–10, middle slightly darker).
- No image shift between flash frame and neighbours (SAD best shift 0,0), so it is not a stutter/duplicate frame.

## What was ruled out (measured)
| Hypothesis | Change | Result |
|---|---|---|
| Sensor/AE actually varying | per-frame `CaptureResult` log (286 frames) | exposure 10 ms and ISO constant, AE/AF/AWB stable, comp 0 → **not the cause** |
| Reconfiguring the session on focus/still | copy session keys from preview to focus/still builders | fixed focus lag/sensor restarts; **flicker unchanged** |
| HDR checker | getters fixed to "off" | no change (25 jumps) |
| Low-light/ASD/lens-dirty flags to stock values | 3 getters aligned with stock | no change (31 jumps) |
| Auto HDR tags (`hdrMode`, `hdrChecker.enabled`) | no longer written | 8 jumps once, later runs back to ~2–5; **inconclusive** (noise) |
| Other vendor request tags | block 4, then **all** `xiaomi.*`/`com.xiaomi.*` at runtime | no change |
| "AI" scene mode | switched off from the UI | no change |
| ImageReader usage flags (0x3 vs 0x20003) | request 0x3 | HAL still reports 0x20003; no change |
| App GL renderer | measure at the camera `SurfaceTexture` (OES) vs output | flashes already present at OES with identical timestamps → **not the app renderer** |
| Plain Camera2 on ids 0/2/5 | probe app, AE_MODE_ON | 0 jumps on the same scene → problem is in what the port requests |

## Where the difference is
- Stock camera session: vendor operation mode CUSTOM (36866 = 0x9002). Port: forced to NORMAL/REGULAR (0x8001) because some camera ids lack the Xiaomi vendor
  characteristic keys that the original code path expects. This is the largest *structural* difference found; whether it matters for the flashes is untested.
- Request-tag differences between stock and port are small (a handful of vendor tags; value diffs: face-detect FULL vs SIMPLE, in-sensor zoom, motion capture).

## EV step response (AE reaction), `data/exclusive-summary.json`
Instantaneous compensation change −6 ↔ +6, sensor-side "gain" fraction reached in one sample interval and 10→90 % rise:

| Configuration (port) | n | step fraction (1 frame) | rise (frames) |
|---|---|---|---|
| default AE, NORMAL op-mode, FPS [14,30] | 3 | ≈ 0.99 | 0 |
| FPS fixed [30,30] | 3 | ≈ 0.99 | 0 |
| explicit antibanding 50 Hz | 3 | ≈ 0.59 | 13 |
| explicit antibanding 60 Hz | 3 | ≈ 0.54 | 16 |
| op-mode CUSTOM on camera id 2 | 3 | ≈ 0.61 | 45 |
| antibanding AUTO (lab switch) | 3 | ≈ 0.99 | 0 |

Reading: with default settings the AE applies a brightness change in **one frame**, which matches the "steps instead of ramp" the eye sees.
Settings that change how the HAL treats AE (explicit antibanding, a different operation mode) produce a gradual response. **Caveats:** small *n*, stimulus is a
forced EV step (not a physical light change), and the stock camera was driven with a different protocol, so this shows a plausible mechanism, not a proven cause or a fix.

## Open questions
- Does explicit antibanding or the CUSTOM operation mode also remove the isolated single-frame flashes at constant exposure, or only change the AE ramp?
- Is the flash a tone-mapping / digital-gain / OES-conversion effect in the HAL (not exposed in `CaptureResult`)?
- Does the same happen on other Xiaomi/HyperOS devices with third-party camera apps? Reports welcome.
