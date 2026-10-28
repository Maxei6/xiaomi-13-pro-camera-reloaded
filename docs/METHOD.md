# How to measure preview flicker objectively

"It looks bad" is not debuggable. These three measurements separate *where* the brightness change happens.

## 1. Screen-record luma (what the user sees)
`adb shell screenrecord --time-limit 8 --size 540x1200 --bit-rate 6000000`, pull the file, then with ffmpeg crop the preview area, scale to 64×64,
convert to gray and take the mean per frame. Count frame-to-frame |Δluma| above 1.5 (8-bit scale).
- Use a **static scene** with stable light; do not move the phone.
- This metric is **noisy**: the same build gave 8 … 36 "jumps" in different runs. Use ≥3 runs per configuration and compare medians; reject runs where the app
  restarted, the screen slept, or two recordings overlapped.
- An *isolated flash* detector (one frame departs from both neighbours which agree with each other) separates glitches from real AE drift.
- Tools: `tools/taglab.sh`, `tools/flicker2_metric.py`.

## 2. Per-frame Camera2 results (what the sensor/ISP reported)
Log for every `TotalCaptureResult`: `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `CONTROL_AE_STATE/MODE/LOCK`, `CONTROL_AE_EXPOSURE_COMPENSATION`,
`CONTROL_AE_TARGET_FPS_RANGE`, `SENSOR_TIMESTAMP`, plus a hash of the whole `CaptureRequest` (to prove the app is not changing the request).
If exposure×ISO is constant in the frames where luma jumps, the jump is **downstream of AE** (ISP/HAL tuning, tone mapping, digital gain, or conversion).
Reading the log over `adb logcat` is enough; no root needed.

## 3. Control experiment: plain Camera2 probe
`probe/` is a tiny app that opens a given camera id with `TEMPLATE_PREVIEW` + `CONTROL_AE_MODE_ON`, logs exposure/ISO/AE state and the mean luma of
each YUV frame. If the probe shows no jumps on the same scene while the port does, the problem is in what the port requests (session/op-mode/tags), not in the sensor.

## 4. EV step response (how AE reacts)
Send an instantaneous `CONTROL_AE_EXPOSURE_COMPENSATION` change (e.g. −6 → +6, then smaller ±1 EV steps) and record the response both in the
reported `exposure×ISO` ("gain") and on screen. Report: fraction of the final change reached in one sample interval, frames needed for 10 → 90 %.
A stock-like auto-exposure ramps (tens of ms to seconds); a "single-frame" response looks like a flicker to the eye when the scene light changes.
Compare against the stock camera with the *same* stimulus where possible (stock uses its own UI slider, so the protocol differs: say so).

## Pitfalls that cost us time
- Two scripts driving the same phone at once (overlapping `screenrecord`, app restarts) silently invalidates every number. One controller only.
- Phone asleep/locked → black frames that still produce a "valid" metric. Check `mWakefulness` and keyguard before every run.
- Wireless ADB drops and the port changes; always re-check the device before concluding anything.
- Do not run `dumpsys media.camera` while measuring; it can stall camera services.
