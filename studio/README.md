# LOOM Studio (Plan B)

Standalone host app (`com.loom.studio`) that runs user-imported on-device models inside LOOM's
virtual-chiplet runtime. Separate module; `app/` (`com.loom.runtime`) is untouched.
Full brief: `docs/PLAN_B_LOOM_STUDIO_PROMPT.md`.

## Build & install (debug only)

```
gradlew :studio:installDebug
adb shell am start -n com.loom.studio/.MainActivity
```

## Telemetry

Same logcat tags and JSON keys as `app/`, so `live_dashboard.bat` works unchanged:

- `LOOM_LIVE`: one JSON line per second, with the extra keys `app` ("studio"), `model`,
  `task`, `goal_metric`, `goal_value`, `metric_value` and `units`
- `LOOM_EVT`: events
- `LOOM_TRX`: chiplet transitions

Quick check without the dashboard:

```
adb logcat -v tag LOOM_LIVE:I LOOM_EVT:I LOOM_TRX:I *:S
```

## Drop 1 test (M1–M4)

1. `build_studio.bat` (runs unit tests, builds, installs, copies efficientdet_lite0/2.tflite to Download)
2. Import → efficientdet_lite0.tflite → Inspect shows "VISION · detector", input [1,320,320,3] uint8, labels
3. Build chiplets → Run: camera with boxes; LOOM pill shows heat · mA · fps/goal
4. Back → Profile (unplugged): ~1–2 min, then each profile shows ms / mA / quality
5. Run again, open the dashboard, press Stress → GPU: the Weaver lowers Display → Inference → Capture
   (cut order), visible in the LOOM_TRX hot-swap panel

Dashboard buttons now go to whichever app is sending (`"app": "studio"` → `com.loom.studio`).

## Milestones

- [x] M0: module, splash, Library, foreground run service, 1 Hz LOOM_LIVE
- [x] M1: .tflite import + ModelInspector (GGUF header read too; .task/.onnx detected)
- [x] M2: generic vision pipeline (camera → preprocess → LiteRT → postprocess → overlay); other tflite models run on synthetic input
- [x] M3: profiler + profiles.json (inference: GPU / CPU×4 / CPU×2; camera 30/15/10)
- [x] M4: generalised Weaver + Fixed/Adaptive/Manual + safety guard + ADPF hints
- [ ] M5: llama.cpp (GGUF) chat
- [ ] M6: MediaPipe .task + ONNX
- [ ] M7: loom.json projects
- [ ] M8: planner (exact + QAOA import)
- [ ] M9: Compare + report
- [ ] M10: NPU profile
