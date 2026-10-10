# LOOM — a virtual-chiplet runtime for adaptive on-device AI

iQOO Hackathon 2026 · Grand Finale · Bengaluru · device: iQOO 15

LOOM is the runtime, not the AI model. The model detects objects; Android provides the camera
and device signals; virtual chiplets separate the workload into stages; the Weaver (Phase 2)
decides how those stages run — and telemetry tells us whether its decisions help.

## Status — Phase 1 (Foundation)

- CameraX Preview + ImageAnalysis (4:3, ~640×480, `KEEP_ONLY_LATEST`, RGBA) on a
  **single-threaded analyzer** — the top of each `analyze()` is the safe boundary.
- Chiplets: **Capture** (frame stride), **Preprocess** (rotate upright), **Inference**
  (MediaPipe EfficientDet, hot-swappable), **Display** (boxes + dashboard). Narrator: Phase 2.
- **Safe-boundary hot-swap**: new detector built on a background thread, swapped at the top
  of the next frame, old detector closed after the swap; swap gap logged in ms.
- Live dashboard: FPS vs target, per-stage ms, p50/p90, detections, confidence, thermal
  status + headroom, raw battery current, airplane-mode indicator, last event and swap.
- Manual profile chips (pre-Weaver) to exercise every profile and the swap path.
- Per-frame CSV in app storage; `tools/summarize_csv.py` summarises it on the laptop.
- `INTERNET` permission is stripped in the manifest: the app **cannot** reach the network.

## Status — Phase 2 (Adaptation)

- **Narrator chiplet**: every 3 s / 6 s / paused, speaks one prioritised sentence (largest =
  nearest first, left / ahead / right) with the phone's offline text-to-speech; caption on
  screen. Template sentences now; the on-device LLM replaces the template next, and the
  template stays as the ECO / fallback path.
- **The Weaver** (`weaver/Weaver.kt`): goal "hold ≥ 20 FPS with the best quality possible".
  Once per second it compares measured FPS and thermal headroom with the goal and changes at
  most one profile on one chiplet, with a human-readable reason on screen and in the CSV.

| Rule | Value |
|---|---|
| Downgrade on FPS | FPS < goal for 3 consecutive s → Narrator → Inference |
| Downgrade on heat | headroom > 0.85 → Narrator → Capture stride → Inference |
| Upgrade | 10 s with FPS ≥ goal + 3 and headroom < 0.60 → Inference → Capture → Narrator |
| Cooldown | 5 s after any change; windows reset |
| Anti-oscillation | downgraded within 30 s of an upgrade → upgrades of that chiplet paused 30 s, doubling to 240 s |

  **Why capture stride is not used to rescue FPS:** `fps_1s` counts processed frames, so
  "1 in 2" caps FPS at half the camera rate and could never meet the goal. Stride is a
  thermal/energy knob; while it is active the dashboard shows the goal capped at
  0.9 × camera FPS ÷ stride.

- **Modes**: Adaptive (Weaver in control) · Fixed HIGH (benchmark Run A; Weaver only
  observes) · Manual (any profile chip; Weaver paused).
- **Stress toggle** (real load, never a faked metric): GPU = a second Lite2 detector on the
  GPU running back-to-back on the live frame; CPU = busy threads on half the cores.
- **Benchmark run**: "Start 15-min run" opens a fresh `loom_run_<mode>_<time>.csv` and stops
  itself at 15:00.
- New CSV columns (appended after the blueprint schema): `display_ms, camera_fps, mode,
  stress, target_fps, narration`.

### Eval 2 demo script
1. Mode **Adaptive**, goal ≥ 20, stress **Off** → Inference settles on Lite2·GPU if it can.
2. Stress **GPU** → FPS drops → after 3 s the Weaver reason appears and one chiplet steps
   down → FPS recovers. The swap line shows the swap gap in ms; the frame counter never resets.
3. Stress **Off** → after 10 s of slack the Weaver steps back up.

### Fixed vs adaptive benchmark (Red Light work)
1. Phone on a stand, same scene, airplane mode, same brightness.
2. Mode **Fixed HIGH** → Start 15-min run. Cool down. Mode **Adaptive** → Start 15-min run.
3. `pull_logs.bat`, then:
   ```
   pip install pandas matplotlib
   python tools/compare_runs.py csv\files\loom_run_fixed_*.csv csv\files\loom_run_adaptive_*.csv
   ```
   Writes `charts/`: FPS over time with Weaver events, % of seconds at goal (headline),
   inference p50/p90, confidence and battery current per profile.

## Status — Heat control (Phase 2.5)

LOOM now treats heat as part of the goal: *hold ≥ 20 FPS with the best quality possible,
without running hotter than the heat limit.*

| Feature | What it does |
|---|---|
| **Pacing** (Capture chiplet, Adaptive only) | Processes at most goal + 3 FPS. The camera gives ~30; processing 23 instead of 30 is ~23% less detector work, with the goal still met. Fixed and Manual process every frame, like an ordinary app. |
| **Forecast heat** | Heat = max(current headroom, Android's 10-second forecast `getThermalHeadroom(10)`), so LOOM steps down *before* the phone gets hot. |
| **Heat limit** | Off / ≤0.75 (cool) / ≤0.85 (default). Above it for 2 s → Narrator → Lite2→Lite0 → fewer frames → GPU→CPU. Upgrades only when heat < limit − 0.15. |
| **True camera FPS** | Counted from the camera sensor (Camera2 capture callback), not from frames reaching the analyzer — a slow model made the old number look like a slow camera. |
| **Capacity** | min(camera ÷ stride, 1000 ÷ p50 total ms): what the pipeline *could* do. Upgrades use it because pacing keeps measured FPS just above the goal on purpose. |
| **Evidence** | CSV columns `thermal_forecast, pace_fps`; `compare_runs.py` adds `heat_over_time.png`, frames of work per minute, and ending headroom fixed vs adaptive. |

Honest limits: headroom is Android's own normalised forecast (not °C). Whether GPU or CPU
runs cooler on the iQOO 15 is not assumed — measure battery current and headroom per profile
during Red Light (phone unplugged), then reorder the heat steps if the data says so.

## Demo setup — phone shows only the camera, laptop shows LOOM's brain

**Phone (fully automatic):** LOOM starts in Adaptive mode and adjusts everything itself —
frame pacing, heat (current + 10 s forecast), battery (below 30% and not charging → goal
drops to 10 FPS), model and compute swaps. The screen shows only the camera and detection
boxes. Long-press anywhere for the engineering view (for us, not for judges).

Defaults: goal **≥ 15 FPS** (enough for walking guidance), heat limit **0.80**, pacing **on**.

**Laptop (live, over USB, no network):** a **React** app (`tools/dashboard/src`, built bundle
committed in `tools/dashboard/dist`, so the laptop needs only Python). Double-click
`live_dashboard.bat` → opens
`http://localhost:8765`:
- Speed vs goal, heat vs limit with the 10 s forecast, battery current, **% of AI work avoided**
- The five chiplets as a live floorplan — profile, ms, level; a tile flashes when it changes
- The Weaver's current status and reason, plus a timestamped feed of every decision and swap
- 3-minute live charts: FPS / goal / capacity, heat / forecast / limit, battery current
- Demo buttons (stress, mode, goal, heat limit, 15-min run) sent to the phone with
  `adb shell am broadcast` — nobody touches the phone

**Unplugged (honest heat/battery numbers):** with the phone on USB once, run `wifi_connect.bat`
(phone Wi-Fi on — airplane mode can keep Wi-Fi on — and on the same network as the laptop). It
switches adb to network mode and saves the address in `.phone_wifi`; unplug the cable and the
dashboard reconnects over Wi-Fi by itself (top-right shows USB or Wi-Fi). Repeat after a phone
restart. If the venue Wi-Fi blocks device-to-device traffic, use the laptop's Mobile hotspot.
The app itself still has no network permission — only Android's own debug bridge uses Wi-Fi.

How it works: the app writes one JSON line per second to logcat (`LOOM_LIVE`) and one per
event (`LOOM_EVT`); `tools/live_dashboard.py` (Python standard library only) reads them with
`adb logcat` and streams them to the page. Each session is saved to `live_logs/`.
`live_dashboard.bat --demo` replays `tools/demo_session.jsonl`, a session recorded from LOOM's
real control code on a simulated phone (rehearsal only; the page is labelled SIMULATED).

What the page shows (light theme): KPIs · the five chiplets as one pipeline, each glowing while
the Weaver changes it · **hot-swap step by step** (requested → new model built in the background
while the old one serves → swapped between frames → old model released, with real timings from
the phone's `LOOM_TRX` events) · **cooling** (phone heat colour, 10 s trend, every heat decision
with the heat 30 s later) · decision log · FPS / heat / battery charts with decision markers.

## Detection + cool-running pass

| Change | Why |
|---|---|
| Detector allowlist: people, vehicles, animals, obstacles (COCO) | Walking guidance only — no TV/laptop/bottle clutter in boxes or speech |
| Steady boxes (Display chiplet tracker: IoU ≥ 0.3 match, smoothing, 2-frame hold) | Boxes stop flickering and jumping, with zero extra AI work |
| Preview capped at ~1280×960 | Less camera-pipeline and screen-composition work |
| Preprocess reuses its two bitmaps | No 1–2 MB allocation per frame → less CPU and garbage-collector heat |
| **Display chiplet = screen brightness** (normal / 35% / 8%) | The screen is a top power draw and a visually impaired user does not need it bright. Heat order (least valuable to a blind user first, voice last): screen → Lite2→Lite0 → frame stride → speak less often. No GPU→CPU step: measured on the iQOO 15, Lite0 takes ~81 ms on the CPU vs ~24 ms on the GPU (CPU stays only as a GPU-failure fallback). Low FPS is rescued only by the detector (the template narrator costs <1 ms, so pausing it can't help and would silence the user) |

## Verification done before the first build

- **Compile check:** all 14 Kotlin source files compiled with the real Kotlin 2.0.21 compiler
  (the version the app uses) against stand-ins for Android, CameraX, MediaPipe and Compose.
  One real portability issue found and fixed (`@OptIn(markerClass = [...])`).
- **Simulated phone (3.5 min):** the real LOOM classes driven by a 30 FPS camera with
  KEEP_ONLY_LATEST delivery, detector latencies measured on the iQOO (Lite2·GPU 72 ms), GPU
  stress, a heat model, Fixed mode and a 25 % battery. Results: 0 errors, frame counter never
  went backwards, no closed model ever used, ≤ 2 models open; Weaver rescued FPS, dimmed the
  screen, backed off a failed upgrade, recovered step by step once cool, and entered battery
  saver. Fixes it drove: narrator no longer paused for FPS, cool band 0.15 → 0.08, forecast
  lead capped at +0.10, stride no longer blocks its own recovery, zone hysteresis in speech
  (26 flip-flopping sentences → 13 calm reminders on a static scene).
- Limits: stand-ins are not the real libraries, and the heat model is not the real phone —
  the on-device run is still the real test.


1. **Models** → `app/src/main/assets/` (exact names, lowercase):

   ```
   curl -L -o app/src/main/assets/efficientdet_lite0.tflite \
     https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/latest/efficientdet_lite0.tflite
   curl -L -o app/src/main/assets/efficientdet_lite2.tflite \
     https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float16/latest/efficientdet_lite2.tflite
   ```

   float16 variants are used for both so GPU and CPU profiles compare the same weights.
   (If you already downloaded int8 files, rename them to the names above; note int8 is
   CPU-oriented and may be slower on the GPU delegate.)

2. Open the folder in Android Studio → let Gradle sync (all versions pinned) → Run on the
   iQOO 15 (USB debugging on).

3. Fresh repo for the in-window build record:

   ```
   git init && git add . && git commit -m "Phase 1: LOOM foundation — chiplets, hot-swap, telemetry"
   ```

## Pulling telemetry

```
adb pull /sdcard/Android/data/com.loom.runtime/files/ ./csv/
python tools/summarize_csv.py csv/files/loom_<timestamp>.csv --target 20
```

A new CSV starts at every app launch; the file name is shown at the bottom of the dashboard.

## Metric definitions (so every number is defensible)

| Column | Meaning |
|---|---|
| `frame_no` | Processed-frame counter; never resets during a session, including across swaps |
| `capture_ms` | Top of `analyze()` → frame accepted (includes any swap work at the boundary) |
| `preprocess_ms` | RGBA → Bitmap + rotation upright + MPImage wrap |
| `inference_ms` | `ObjectDetector.detect()` wall time |
| `display_ms` | Mapping results + publishing to the UI (Compose draw not included) |
| `total_ms` | Top of `analyze()` → result published |
| `fps_1s` | Frames fully processed in the trailing 1000 ms (stride-skipped frames don't count) |
| `mean_conf` | Mean top-category score over detections in that frame (0 if none) |
| `thermal_status` | Android thermal **category** — not a temperature |
| `thermal_headroom` | `getThermalHeadroom(0)`, last valid value; ~1.0 ≈ throttling point |
| `battery_current_ua` | Raw `BATTERY_PROPERTY_CURRENT_NOW`; **calibrate sign/units on the iQOO** |
| `weaver_event` | Human-readable reason for any profile change (manual for now) |
| `swap_gap_ms` | Frame-thread time spent swapping the detector reference + closing the old one |

## Profiles

| Chiplet | HIGH | BALANCED | ECO |
|---|---|---|---|
| Inference | Lite2 · GPU | Lite0 · GPU | Lite0 · CPU |
| Capture | every frame | 1 in 2 | 1 in 3 |

If the GPU delegate fails at startup the app logs the failure and falls back to Lite0 · CPU.

## First on-device tests

1. Boxes line up with objects in portrait.
2. Tap each Inference chip ~10 times quickly — no crash, frame counter keeps climbing, each swap
   shows a build time and swap gap.
3. Airplane mode on → header shows `✈ AIRPLANE ON`.
4. Pull the CSV and run the summary script.

## Attributions

- [CameraX](https://developer.android.com/training/camerax) — Apache 2.0
- [MediaPipe Tasks](https://github.com/google-ai-edge/mediapipe) — Apache 2.0
- EfficientDet-Lite0 / Lite2 object-detection models (MediaPipe model zoo, COCO labels) — Apache 2.0
- Jetpack Compose, AndroidX, Kotlin coroutines — Apache 2.0
- pandas (laptop analysis) — BSD 3-Clause
- matplotlib (charts) — PSF-based Matplotlib License
