# Build brief: LOOM Studio (Plan B)

> **How to use this file:** paste the whole thing into your AI coding agent (Claude Code, Cursor, etc.) as the first message, with the existing `loom` repo open, or follow it yourself section by section. Build milestone by milestone and test each one on the phone before starting the next.

---

## 1. What you are building, in one paragraph

**LOOM Studio** is a standalone Android app (APK) that runs **any on-device AI model the user imports**, and wraps it in LOOM's virtual-chiplet runtime so it stays cool and saves battery. The user imports a model file (or a small "LOOM project" folder). LOOM Studio inspects the model, works out what kind of AI it is (vision, language/chat, audio, generic), **automatically builds a pipeline of virtual chiplets for it**, benchmarks each chiplet's power levels on this phone, and then runs it under the **Weaver**, which balances quality against the phone's real heat and battery, exactly like the current LOOM camera app does for object detection.

**Why Plan B exists:** Plan A is LOOM built into the phone's OS, managing every app. If a phone brand doesn't allow OS changes, LOOM Studio still delivers the idea: no root, no OS changes, an ordinary APK that anyone can install.

**Be honest about the limit:** an ordinary APK can only manage **the AI it runs itself**. It can't control other apps' AI or the phone's clock speeds. That's why Studio is a *host*: models run *inside* LOOM so LOOM can control them.

---

## 2. Hard rules

1. **Do not touch the existing camera app** (`app/`, package `com.loom.runtime`). It's being evaluated. Create a **new Gradle module** `studio/`, with application id **`com.loom.studio`**. Copy code you reuse; don't edit the original.
2. **Pure APK:** no root, no hidden APIs, no system permissions. minSdk **30** (needs `PowerManager.getThermalHeadroom`), targetSdk **35**.
3. **Imported files are untrusted data.** Never execute code from an import. A "LOOM project" is **declarative JSON only**: no scripts, no plugins, no downloading code. Validate every field. Cap import size (e.g. 8 GB) and check free storage first.
4. **Works fully offline.** No network permission in the release build, as in the current app.
5. **Keep the telemetry format identical** to the current app (logcat tags `LOOM_LIVE`, `LOOM_EVT`, `LOOM_TRX`, same JSON keys, plus new ones), so the existing laptop dashboard (`tools/live_dashboard.py`) works with Studio from day one.
6. Kotlin + Jetpack Compose, coroutines, a single-activity app. Long runs happen in a **foreground service** so Android doesn't kill them.

---

## 3. Code to reuse from the current repo

| Existing file | What to take | Change needed |
|---|---|---|
| `core/Core.kt` | `Level` (HIGH/BALANCED/ECO), `Chiplet`, `AdaptiveChiplet`, `Transitions`, `Clock` | Generalise: profiles come from data, not hard-coded EfficientDet |
| `weaver/Weaver.kt` | Control loop: sense → plan → reflex → hot-swap; heat limit 0.80, 5 s cooldown, back-off, forecast | Make the **goal metric pluggable** (FPS, tokens/s, latency) and the **cut order data-driven** |
| `weaver/PolicyTable.kt` | Loads a plan table, `lookup(goal, heat)` | One policy file **per imported model** |
| `telemetry/DeviceSignals.kt` | Thermal headroom + forecast, thermal status, battery µA, % and charging | Reuse as-is |
| `telemetry/TelemetryBus.kt` | 1 Hz `LOOM_LIVE` JSON line + events | Add fields: `model`, `task`, `goal_metric`, `goal_value`, `metric_value` |
| `chiplets/InferenceChiplet.kt` | **Background build + swap between frames** (build ~300 ms, swap ~0.02 ms) | Generalise to any runtime backend |
| `chiplets/CaptureChiplet.kt` | Camera2 real sensor frame-rate control | Becomes the `CameraSource` chiplet |
| `quantum/` (Python) | QUBO + QAOA + brute-force verifier | Accept a profile table exported from Studio (section 9) |

---

## 4. What users can import

Support these formats, in this priority order:

| Format | Runtime to use | Typical models | Compute options |
|---|---|---|---|
| `.tflite` (LiteRT) | LiteRT / TensorFlow Lite interpreter | classifiers, detectors, segmenters, pose, small audio models | CPU (XNNPACK, threads), **GPU delegate**, **NPU** via the Qualcomm QNN delegate where available |
| `.task` (MediaPipe bundle) | MediaPipe Tasks (vision / text / audio / LLM inference) | MediaPipe detectors, Gemma-style `.task` LLMs | as MediaPipe allows (CPU/GPU) |
| `.gguf` | **llama.cpp** built for Android via JNI (NDK) | local LLMs / chatbots (Llama, Qwen, Gemma, Phi GGUF) | CPU threads; GPU (Vulkan/OpenCL) only if it proves stable on the iQOO |
| `.onnx` | ONNX Runtime Android | anything exported to ONNX | CPU, NNAPI/QNN execution provider where supported |
| `loom.json` project folder | any of the above | multi-model pipelines | per stage |

Notes for the builder:
- **Check current library versions and APIs before coding.** These ecosystems change fast (e.g. MediaPipe's LLM API vs LiteRT-LM; NNAPI is deprecated on newer Android). Pick what works on the iQOO 15 today.
- The NPU is optional. Get CPU and GPU working first and add the NPU as an extra Inference profile later.
- Import goes through the **Storage Access Framework** (`ACTION_OPEN_DOCUMENT`) and copies the file into app-private storage. Show size, detected type, and a warning when the model is likely too big for RAM.

**Size guidance (to show in the UI):** small vision/audio models are a few MB and trivial. LLMs: 0.5–3 B parameters at 4-bit (≈0.4–2 GB) are the sweet spot on a flagship phone; 7–8 B at 4-bit (≈4–5 GB) can load on a 12–16 GB phone but will be slow and hot. Measure on the device; don't promise numbers.

---

## 5. Model inspection: working out what kind of AI it is

On import, build a `ModelCard`:

```kotlin
enum class TaskKind { VISION, LLM, AUDIO, TEXT, GENERIC }

data class TensorSpec(val name: String, val shape: IntArray, val dtype: String)

data class ModelCard(
    val id: String, val displayName: String, val format: String,   // tflite | task | gguf | onnx
    val sizeBytes: Long, val kind: TaskKind,
    val inputs: List<TensorSpec>, val outputs: List<TensorSpec>,
    val labels: List<String>?,          // from TFLite metadata / label file, if any
    val llm: LlmInfo?,                  // GGUF: architecture, params, quant, context length
    val confidence: Float,              // how sure the auto-detect is (0..1)
)
```

Detection rules (the user can always override in the UI or in `loom.json`):
- **GGUF** → `LLM` (read the GGUF header for architecture, context length and quantisation).
- **`.task`** → by MediaPipe task type in the bundle.
- **TFLite / ONNX:** look at the input tensors.
  - `[1,H,W,3]` or `[1,3,H,W]` float/uint8 → **VISION**. Then classify by outputs: boxes+scores+classes → detector; `[1,N]` → classifier; `[1,H,W,C]` → segmentation; keypoints → pose.
  - `[1,N]` float audio samples or a spectrogram → **AUDIO**.
  - int32/int64 token ids `[1,seq]` → **TEXT**.
  - anything else → **GENERIC** (raw tensors in, raw numbers out; still benchmarked and managed by the Weaver).
- Read **TFLite metadata** (normalisation mean/std, label files) when it's present.

---

## 6. Auto-building the virtual chiplets

Each `TaskKind` maps to a **pipeline template**. Every stage is a chiplet with the same interface as today: **power profiles, live cost, live quality, hot-swap.**

```kotlin
interface StudioChiplet : AdaptiveChiplet {
    val profiles: List<Profile>              // ordered best quality -> lowest power
    val current: Profile
    fun cost(): CostSample                   // ms per unit, units/s, estimated mA share
    fun quality(): Double?                   // task-specific proxy, null if unknown
    suspend fun prepare(p: Profile)          // build in the background (e.g. new interpreter)
    fun swap()                               // switch at the next unit boundary (frame / token / chunk)
}

data class Profile(val level: Level, val label: String, val knobs: Map<String, Any>)
```

| Task | Chiplets (in order) | The knobs each chiplet's profiles turn |
|---|---|---|
| **VISION** | `CameraSource` → `Preprocess` → `Inference` → `Postprocess` → `Display` (+ optional `Narrator`) | camera fps 30/15/10 (real Camera2 rate); input resolution; delegate GPU/CPU/NPU, threads, fp16/int8 variant; NMS/top-k; overlay or brightness; speech interval |
| **LLM (chat)** | `PromptInput` → `Tokenizer` → `Inference` → `Sampler` → `TextStream` (+ optional `Voice`) | threads (e.g. 6/4/2), GPU layers on/off, context length, batch size, **token-rate cap** (tokens/s), max reply length; streaming UI refresh rate; TTS on/off |
| **AUDIO** | `MicSource` → `Features` → `Inference` → `Output` | sample/hop rate, window size, run every N ms, delegate |
| **TEXT / GENERIC** | `Input` → `Inference` → `Output` | delegate, threads, run interval, batch |

**Model-variant profiles:** if the user imports two sizes of the same model (as we do with EfficientDet Lite2 and Lite0), or a project lists `variants`, the Inference chiplet's profiles include **swapping the model itself**, built in the background and swapped between units, as today.

**Cut order** (what the Weaver lowers first when hot): a default per task, editable in the project file. Rule: cut what the user notices least first.
- Vision: Display → Inference → Camera → Narrator.
- LLM: Voice → UI refresh → token-rate cap → threads → context length.

---

## 7. The profiler: measure on this phone, at import

Run automatically after import (about 1–3 minutes; show a progress screen):
1. For each chiplet profile, run a fixed workload: sample images or camera for vision, a fixed prompt for LLMs, a bundled clip for audio.
2. Record **latency** (p50/p90), **throughput** (fps or tokens/s), **battery current** (BatteryManager µA, averaged; ask the user to unplug for accuracy) and **heat slope**.
3. **Quality proxy:** compare each lower profile's output with the HIGH profile on the same inputs. Vision uses box IoU/agreement or top-1 agreement; LLMs use the token match rate on the fixed prompt; audio uses transcript or label agreement. HIGH scores 1.0.
4. Save the result as `profiles.json` next to the model. This table drives the Weaver and the quantum planner.

Re-run the profiler from the model screen whenever the user wants.

---

## 8. The generalised Weaver

Same brain as today, made generic:
- **Goal:** chosen per model, e.g. "≥ 15 FPS" (vision), "≥ 8 tokens/s" (LLM), "result within 300 ms" (audio). The user picks it from presets.
- **Sense (every 1 s):** thermal headroom and 10 s forecast, thermal status, battery current and %, charging state, plus the goal metric from the chiplets.
- **Plan (every 3 s):** look up this model's policy table (section 9) for the current goal and heat band; move toward that profile set.
- **Reflex:** if the forecast is over **0.80**, lower **one** chiplet following the cut order. If the goal metric is short and heat is OK, raise one. 5 s cooldown, hysteresis, back-off. Never flicker.
- **Hot-swap:** `prepare()` in the background, `swap()` at the next frame/token/chunk boundary; emit `LOOM_TRX` phases `requested → built → swapped`.
- **CPU hints:** use **ADPF `PerformanceHintManager`** (API 31+) for the inference threads, reporting target vs actual work duration, so Android gives the right CPU capacity rather than maxing clocks.
- **Modes:** Adaptive (Weaver decides), Fixed HIGH (the baseline for comparison), Manual.
- **Safety guard:** if thermal status reaches SEVERE in Fixed mode for 20 s, switch to Adaptive (we learned this the hard way: Android closes the camera and drops Wi-Fi otherwise).

---

## 9. Quantum planner, per model

- Studio exports `profiles.json` (chiplets × profiles with measured mA, speed, quality) plus the phone's heat model.
- The existing `quantum/` scripts gain a mode: `python quantum/solve_policy.py --profiles studio_export.json`, which builds the QUBO from **this model's** chiplets, runs QAOA in Qiskit, verifies by brute force, and writes `loom_policy.json`.
- The user copies the policy back (adb push, or import it in Studio's model screen).
- **On-device fallback:** when no quantum policy exists, Studio computes the same table by exact search on the phone. The combinations are small: e.g. 5 chiplets × 3 levels = 243. Label it "classical exact" vs "quantum (QAOA), verified" in the UI.

---

## 10. The "LOOM project" format (`loom.json`)

An optional folder: `loom.json` + model files + optional labels and sample inputs. Example:

```json
{
  "loom_project": 1,
  "name": "Street sign reader",
  "task": "vision",
  "goal": { "metric": "fps", "value": 15 },
  "source": { "type": "camera", "fps_profiles": [30, 15, 10] },
  "models": [
    { "id": "det_hi", "file": "detector_large.tflite", "role": "inference", "level": "HIGH" },
    { "id": "det_lo", "file": "detector_small.tflite", "role": "inference", "level": "BALANCED" }
  ],
  "preprocess": { "resize": [320, 320], "normalize": { "mean": [127.5], "std": [127.5] } },
  "postprocess": { "type": "detection", "score_threshold": 0.4, "labels": "labels.txt" },
  "output": { "overlay": true, "speak": false },
  "cut_order": ["display", "inference", "source"],
  "heat_limit": 0.80
}
```

Rules: a JSON schema plus a validator with clear error messages; unknown keys are rejected; file paths stay inside the project folder (no `..`). Chaining several models (e.g. detector → classifier) is allowed as a list of stages, each becoming its own chiplet.

---

## 11. Screens

1. **Library:** imported models as cards (name, type icon, size, "last run: −38% battery vs Fixed"). FAB: **Import model / Import project**.
2. **Import → Inspect:** detected type with confidence, inputs/outputs, labels, RAM warning, override dropdown. Button: **Build chiplets**.
3. **Model detail:** the generated chiplet pipeline (same visual style as the dashboard's chiplet cards), each chiplet's profiles with measured ms, mA and quality; goal picker; cut order (drag to reorder); policy source (quantum or classical); **Re-profile** button.
4. **Run:** task UI full-screen, with a small LOOM pill (heat, mA, goal met ✓) that expands into a Weaver panel.
   - Vision: camera preview with overlays.
   - LLM: chat screen with streaming replies.
   - Audio: live transcript or labels.
   - Generic: input picker and output numbers.
5. **Compare:** run the same workload 10–15 min in Fixed then Adaptive; show battery, heat, speed and quality side by side and save a report (CSV + JSON).
6. **Settings:** heat limit, telemetry on/off, export logs, about.

Visual style: same brand as LOOM (black splash with the infinity animation, cyan → blue gradient `#3DD6FF → #1A5CFF`); light theme in the app.

---

## 12. Suggested module layout

```
studio/
  src/main/java/com/loom/studio/
    StudioApp.kt, MainActivity.kt
    importer/      ImportManager.kt, ModelInspector.kt, ProjectParser.kt (+ schema)
    runtime/       Backend.kt (interface), LiteRtBackend.kt, MediaPipeBackend.kt,
                   LlamaCppBackend.kt (JNI), OnnxBackend.kt
    chiplets/      StudioChiplet.kt, CameraSource.kt, MicSource.kt, PromptInput.kt,
                   Preprocess.kt, InferenceChiplet.kt, Postprocess.kt, Sampler.kt,
                   TextStream.kt, Display.kt, Voice.kt
    pipeline/      PipelineFactory.kt (TaskKind -> chiplets), Pipeline.kt
    profiler/      Profiler.kt, QualityProxy.kt
    weaver/        Weaver.kt (generalised), PolicyTable.kt, ExactPlanner.kt, Adpf.kt
    telemetry/     DeviceSignals.kt, TelemetryBus.kt (same tags/format)
    service/       RunService.kt (foreground)
    ui/            Library, Inspect, ModelDetail, Run (Vision/Chat/Audio/Generic), Compare, Settings
  src/main/cpp/    llama.cpp submodule + JNI bridge (CMake)
```

```kotlin
interface Backend {
    suspend fun load(card: ModelCard, knobs: Map<String, Any>): Session   // build in background
}
interface Session : AutoCloseable {
    fun run(inputs: Map<String, Any>): Map<String, Any>                    // vision/audio/generic
    fun generate(prompt: String, params: GenParams, onToken: (String) -> Unit) // LLM
}
```

---

## 13. Milestones (each ends with a test on the iQOO 15)

| # | Milestone | Done when |
|---|---|---|
| M0 | `studio` module builds; splash, Library screen, foreground service, telemetry lines appear in logcat | The existing dashboard shows Studio's heat and battery live |
| M1 | Import `.tflite` + inspector | Importing `efficientdet_lite0.tflite` detects "VISION · detector" with the correct inputs/labels |
| M2 | Generic vision pipeline (camera → preprocess → LiteRT → postprocess → overlay) | Any imported TFLite detector or classifier runs on the camera with boxes or labels |
| M3 | Profiler + `profiles.json` | Profiles show measured ms, mA and quality for CPU/GPU, threads and camera fps |
| M4 | Generalised Weaver + Fixed/Adaptive/Manual + safety guard | Stress → GPU from the dashboard makes the Weaver step chiplets down in cut order, logged in `LOOM_TRX` |
| M5 | LLM chat via llama.cpp (GGUF), with LLM chiplets | A 1–3 B 4-bit GGUF chats offline; the Weaver lowers threads and the token-rate cap when hot, logged |
| M6 | MediaPipe `.task` and ONNX backends | One `.task` model and one `.onnx` model each run through the same pipeline |
| M7 | `loom.json` projects + multi-model chains | The example project in section 10 imports and runs; invalid projects show clear errors |
| M8 | Planner: on-device exact + export/import for the Qiskit QAOA policy | The model screen shows the policy source, and the Weaver follows the plan (dashboard plan strip) |
| M9 | Compare screen + report | 15-min Fixed vs Adaptive for one vision model and one LLM, with numbers saved |
| M10 | NPU profile (QNN delegate), if available on the device | NPU appears as an extra Inference profile, measured like the others |

---

## 14. Tests and acceptance

- **Unit:** inspector classification on 10+ sample models; `loom.json` validator (good and bad files, path traversal); Weaver decisions on a recorded telemetry trace (same input → same decisions); exact planner == brute force.
- **On the device:**
  1. Import → run → re-profile for each format.
  2. A 15-minute Adaptive run that never exceeds thermal status LIGHT/MODERATE.
  3. Kill/resume: the service survives screen-off.
  4. A 4 GB model import with low storage fails gracefully.
  5. A corrupted file shows an error and never crashes.
- **Measured claim to reproduce:** at least one vision model and one LLM showing a battery and heat reduction vs Fixed at similar quality. Report the real numbers, whatever they are; don't tune the demo to look good.

---

## 15. How to pitch Plan B (one line for the judges)

> "If a brand won't open its OS, LOOM ships as an app: import any AI model, image, chat or audio, and LOOM builds the virtual chiplets for it automatically, measures them on your phone, and keeps it cool with the same Weaver and quantum-planned policy."
