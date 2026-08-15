# Current implementation status

## Verified in the sandbox

- Android Studio project configuration is valid.
- Kotlin source compilation and the expanded unit-test suite pass with Android SDK 37.0 and JDK 21.
- The debug APK packages successfully.
- The ARCore manager now creates one session, resumes only from Activity lifecycle callbacks, preserves surface setup ordering, and throttles frame ownership with a bounded in-flight flag.
- SafetyDecisionEngine tests cover unknown tracking, stable distance, fast approach/TTC, and negative-obstacle confirmation.
- The app includes three-column lower-scene sampling for possible drop-offs, a five-by-four multi-point depth corridor for nearest reliable obstacle distance, filtered IMU motion state, thermal-aware frame-rate policy, Hindi TTS, haptic priority feedback, and audible stereo left/right beep fallback.
- Reliable clear space is shown with green status and measured distance; a reliable nearby surface is shown as a Hindi obstacle with measured distance and warning color/feedback; unknown depth never becomes green.
- Camera permission is now checked before both Activity startup and ARCore session creation. If permission is denied or revoked, the app remains open with a Hindi explanation instead of crashing during `onResume()`.
- The official ONNX Runtime pre/post-processing YOLOv8n model is bundled locally and loaded lazily from assets when the user presses `वस्तु खोजें`; Android uses ONNX Runtime plus ONNX Runtime Extensions.
- ONNX detections are mapped to ARCore depth for approximate distance and clock direction, with Hindi aliases for common COCO classes. The current asset is fixed-vocabulary YOLOv8, not true promptable YOLO-World.
- ARCore horizontal upward-facing plane tracking now supplies a ground reference to negative-obstacle analysis. Vibration amplitude, TTS throttling, and stereo beep feedback were optimized.
- Audio alerts now use three preloaded short SoundPool clips with left/right gain panning instead of generating and streaming a fresh PCM buffer for every alert. The previous AudioTrack underrun path is removed.
- Depth safety now uses one shared per-frame depth sampler and reusable ground-profile arrays. Thermal policy is cached, plane checks are throttled, and motion switching uses hysteresis with a 450 ms moving confirmation and 1.5 s still confirmation. Target FPS changes are applied only when the mode changes.
- ARCore rendering now uses `RENDERMODE_WHEN_DIRTY` with a rate-limited main-thread render request, while perception work runs on one low-priority bounded executor. Duplicate/stale ARCore timestamps are rejected, and image ownership is closed safely on both accepted and rejected handoffs.
- Depth values at the far saturation threshold (7,800 mm and above) are not treated as reliable clear space. The Hindi depth reason is logged, and the safety engine receives unknown depth so it remains CAUTION rather than green.
- The detector keeps a CPU baseline and exposes optional NNAPI with `CPU_DISABLED`; failed or unsupported NNAPI setup falls back to CPU. A developer-only provider benchmark API and usage guide are included. NNAPI is not required for safety and is treated as optional because Android 15 deprecates it.
- Thermal policy now combines thermal status with `getThermalHeadroom(10)` sampled at the documented 10-second cadence. A light thermal tier reduces optional workloads before severe throttling while preserving the Reflex Shield loop.
- Unit tests now cover saturated far depth as unreliable and mixed valid/saturated samples as usable when a nearby reliable surface exists. The debug APK was rebuilt successfully after these changes.
- The approved Storyteller design is documented separately. The recommended first runtime boundary is LiteRT-LM behind a `StorytellerEngine` adapter, with Gemma 3n E2B limited to Enhanced-tier benchmarking and SmolVLM-256M retained only as a lower-memory experiment. No VLM is bundled or connected yet; the closed result schema, expiry rules, conservative fusion policy, and physical-device acceptance gates are defined before implementation.

## Deliberate boundaries in this milestone

The large on-device search model and quantized Storyteller VLM are not bundled in this source package. They require a selected model, license review, device-specific quantization, and real-device profiling. The current app therefore reports model availability honestly and does not fabricate detection results.

A physical Android device was not connected during sandbox verification. ARCore depth behavior, camera timestamps, negative-obstacle thresholds, audio routing, and haptics must be validated on the target phone matrix. The three-column ground-profile detector is a conservative candidate detector, not a guarantee of pothole or stair recognition.

## Release gate

Do not treat this debug APK as collision-proof or as a finished mobility aid. It is the Android Studio foundation and UI/safety orchestration milestone. Real-world safety validation and model integration are required before any production or public distribution.
