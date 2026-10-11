# LOOM Studio (Plan B)

Standalone host app (`com.loom.studio`) that runs user-imported on-device models inside LOOM's
virtual-chiplet runtime and keeps them under a heat limit. Separate module; `app/`
(`com.loom.runtime`) is untouched. Full brief: `docs/PLAN_B_LOOM_STUDIO_PROMPT.md`.

**Honest limit:** Studio manages only the AI it runs itself. It cannot slow down other apps or
change the phone's clocks (that needs OS changes, i.e. Plan A).

## Build and install (debug only)

Double-click `build_studio.bat` with the phone on USB. It:

1. restores or fast-forwards git from `loom_repo.bundle`
2. picks a JDK 17–23, downloading Temurin 21 if needed
3. fetches llama.cpp b11552
4. runs the unit tests
5. builds, installs and launches the app
6. copies sample models and projects to the phone's Download folder
7. checks LOOM_LIVE

Logs go to `loom_studio_logs\`.

## What runs

| Import | Runtime | Pipeline (chiplets) | Profiles the Weaver turns |
|---|---|---|---|
| `.tflite` vision | LiteRT 1.4.2 | Capture → Preprocess → Inference → Postprocess → Display (+ Classifier, Voice) | camera 30/15/10 · GPU / NNAPI-NPU / CPU×4 / CPU×2 · overlay + brightness |
| `.tflite` other | LiteRT | Input → Inference → Output (synthetic input) | run interval · delegate/threads |
| `.onnx` | ONNX Runtime 1.29 (QNN EP) | same as tflite | QNN HTP (NPU) / CPU×4 / CPU×2 |
| `.task` LLM | MediaPipe LLM Inference 0.10.24 | Prompt → Tokenizer → Inference → Sampler → TextStream → Voice | GPU/CPU · rate cap · UI refresh · voice |
| `.task` vision | inner `.tflite` via LiteRT | as `.tflite` | as `.tflite` |
| `.gguf` | llama.cpp (JNI, CPU) | Prompt → Tokenizer → Inference → Context → Sampler → TextStream → Voice | threads 6/4/2 (live) · context 4096/2048/1024 · rate cap · UI · voice |
| `loom.json` folder | any of the above | + model variants as profiles, detector → classifier chain | per loom.json |

## Planner (M8)

- **On the phone:** Run → LOOM panel → *Plan (exact)* searches every combination exactly and saves `policy.json`.
  The unit test checks that the exact search reproduces app/'s QAOA-verified policy plan for plan.
- **Quantum:** use *Export for QAOA* (it saves `Download/LOOM/studio_export.json`), then run
  `python quantum/solve_policy.py --profiles studio_export.json`. Copy
  `quantum/results/studio_policy.json` to the phone and use *Import policy*. The UI then shows
  "quantum (QAOA), verified".

## Compare (M9)

Run → LOOM panel → *Compare 15 min* (or *2 min* for a quick check). This runs Fixed HIGH, then a
cool-down, then Adaptive. The result is saved as CSV + JSON, and *Save to Download/LOOM* exports
it. The Library card then shows the result, e.g. "−38% battery vs Fixed".

## Dashboard over Wi-Fi

- `wifi_pair.bat` pairs the phone once using Android 11+ Wireless debugging. It survives Wi-Fi
  reconnects, and the dashboard rediscovers the phone over mDNS.
- If the phone still isn't found, the dashboard writes `live_logs/wifi_diag.txt` explaining why:
  different subnet, router client isolation, or network debugging switched off.

## Telemetry

Logcat tags `LOOM_LIVE` (1 Hz), `LOOM_EVT` and `LOOM_TRX` use the same keys as app/. Studio also
sends `app`, `model`, `task`, `goal_metric`, `goal_value`, `metric_value`, `units`, `adpf` and
`quality`, plus `plan` and `run` (Compare). The dashboard's buttons go to whichever app is
streaming.

## Tests

36 JVM unit tests:
- classifier (13 models)
- GGUF header reader
- Weaver trace determinism, plan following and safety guard
- quality proxy
- loom.json validator (good, bad, path traversal)
- exact planner vs brute force and vs app/'s QAOA policy

## Milestones

- [x] M0: module, splash, Library, foreground run service, 1 Hz LOOM_LIVE
- [x] M1: import + ModelInspector (tflite / onnx / task / gguf)
- [x] M2: generic vision pipeline
- [x] M3: profiler + profiles.json (+ LLM profiler)
- [x] M4: generalised Weaver + Fixed/Adaptive/Manual + safety guard + ADPF
- [x] M5: llama.cpp GGUF chat with LLM chiplets
- [x] M6: MediaPipe .task + ONNX backends
- [x] M7: loom.json projects + model variants + detector → classifier chain
- [x] M8: exact planner on the phone + QAOA export/import
- [x] M9: Compare screen + CSV/JSON report
- [x] M10: NPU profiles (NNAPI for tflite, QNN HTP for onnx), measured like the others

The boxes mean the code is written. Each still has to be confirmed on the iQOO 15.

## LOOM Guard: cool any app (v1.1)

Guard keeps the whole phone under a heat limit while **any** app is in front: games, Camera, video.
It runs in the background run service. One step at a time, with hysteresis like the Weaver, it changes
phone-wide settings:

| Level | Lever | Permission (no root) |
|---|---|---|
| 1 | screen brightness → 60 % | Modify system settings (app-op) |
| 2 | peak refresh rate → 60 Hz | WRITE_SECURE_SETTINGS (`adb pm grant`, done by build_studio.bat) |
| 3 | Android battery saver | WRITE_SECURE_SETTINGS |

- It steps up after 10 s above the limit (3 s at SEVERE). It steps down after 30 s below limit − 0.08.
- Every original value is saved first and restored when the phone cools down, Guard is switched off, or the
  service stops. Values are also restored on the next start after a crash.
- Each decision is a `LOOM_EVT` line. `LOOM_LIVE` carries a `guard` object
  (`on, level, levers, limit, fg_app, last, decisions, perms`). The dashboard shows a Guard strip and on/off buttons.
- It does not change another app's code or the CPU/GPU clocks directly. If a vendor ROM ignores the AOSP
  refresh key, Guard says so in its log; it doesn't claim the cap worked.
- The optional heat pill over other apps needs "Display over other apps". The front-app name needs "Usage access".

Dashboard / adb: `adb shell am broadcast -a com.loom.studio.CONTROL -p com.loom.studio --es cmd guard --es value on|off|0.75`

## Detector fix (v1.1)

`efficientdet_lite0/2.tflite` have a raw head: `[1,19206,4]` box offsets plus `[1,19206,90]` scores, with no NMS
inside the model. Studio now decodes the EfficientDet anchors (levels 3–7, 9 per cell, scale 3.0) and runs NMS.
On a reference image, the bus and three people come out within a few pixels of ground truth
(`AnchorDecoderTest`, `ClassifierTest`). Cards imported before this fix upgrade themselves automatically.
