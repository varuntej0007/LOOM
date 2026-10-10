# LOOM Studio sample projects

- `street_sign_reader/` — the loom.json from the brief (§10). `build_studio.bat` copies it to the phone's
  `Download/LOOM_sample_project/` together with `detector_large.tflite` (= efficientdet_lite2) and
  `detector_small.tflite` (= efficientdet_lite0). Import → Import LOOM project → pick that folder.
  The two detectors become the Inference chiplet's model variants (hot-swapped like any profile).
- `invalid_project/` — must be REJECTED with clear errors (unknown key, path traversal).
  Copied to `Download/LOOM_invalid_project/` for the on-device check.
