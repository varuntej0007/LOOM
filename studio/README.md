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

## Milestones

- [x] M0: module, splash, Library, foreground run service, 1 Hz LOOM_LIVE
- [ ] M1: .tflite import + ModelInspector
- [ ] M2: generic vision pipeline
- [ ] M3: profiler + profiles.json
- [ ] M4: generalised Weaver
- [ ] M5: llama.cpp (GGUF) chat
- [ ] M6: MediaPipe .task + ONNX
- [ ] M7: loom.json projects
- [ ] M8: planner (exact + QAOA import)
- [ ] M9: Compare + report
- [ ] M10: NPU profile
