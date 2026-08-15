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
- The approved Storyteller design is documented separately. The recommended first runtime boundary is LiteRT-LM behind a `StorytellerEngine` adapter, with Gemma 3n E2B limited to Enhanced-tier benchmarking and SmolVLM-256M retained only as a lower-memory experiment. No VLM model is bundled or invoked yet.
- The model-independent Storyteller foundation now includes a closed hazard/severity/region result schema, confidence and expiry validation, a one-frame latest-image store with replacement instead of queueing, a disabled fallback engine, lifecycle-safe close behavior, and JVM unit tests. It cannot raise a VLM-only emergency and cannot downgrade Reflex Shield safety.
- The Phase 1/2 audit found and fixed a negative-obstacle edge case: saturated far profile samples such as 7,867 mm are now rejected as unknown ground instead of being interpreted as a pothole/drop. The regression is covered by a JVM test. The audit report records static verification and remaining physical-device gates.
- LiteRT-LM Android 0.16.0 is now declared as a dependency, with a non-enabled CPU/GPU/NPU `LiteRtLmStorytellerEngine` adapter and Enhanced-tier benchmark harness. No model path is supplied by MainActivity and no VLM model is bundled; the existing Storyteller button remains an unavailable fallback.
- Phase 3 Cloud Agent architecture is documented as an optional, user-consented online assistant. A model-independent `CloudAssistantGateway` contract, offline fallback, consent/network/thermal policy, bounded request schema, HTTPS relay client, response expiry checks, and cloud safety-authority rejection tests are now present. No provider endpoint, API key, continuous upload, or cloud-controlled safety decision is configured.
- Phase 4 now includes a provider-neutral HTTPS relay contract and local FastAPI reference validator under `relay/`. It enforces a short-lived bearer-token boundary, strict JSON/schema/TTL/image limits, replay protection, rate limiting, generic error responses, no provider by default, and `safety_authority: none`. The reference relay has five passing contract tests and is not deployed or connected to a provider.
- The consolidated safety hardening now treats an untracked ARCore ground plane as CAUTION even when a far surface depth is otherwise reliable; close obstacles and negative-drop emergencies still retain priority. A bounded one-running/one-pending perception queue, safety-aware UI throttling, and a one-shot perception watchdog reduce main-thread load and prevent silent stalls.
- The supplied 2026-08-15 crash log exposed an older-APK camera-permission resume failure and a thermal listener removal failure. Current source now catches startup permission/runtime races, and `ThermalDutyManager` removes its listener only after confirmed registration, making repeated pause/resume and partial startup idempotent. The diagnosis and clean-install test procedure are documented in `crash-fix-2026-08-15.md`.
- Offline Search now maps the full bundled COCO vocabulary to Hindi labels with safe English fallback for unknown future classes. Common Hindi aliases for bicycle, motorbike, vehicles, animals, bags, mobile, table, TV, and other fixed-vocabulary objects are supported. No open-vocabulary claim is made.

## Deliberate boundaries in this milestone

The large on-device search model and quantized Storyteller VLM are not bundled in this source package. They require a selected model, license review, device-specific quantization, and real-device profiling. The current app therefore reports model availability honestly and does not fabricate detection results.

A physical Android device was not connected during sandbox verification. ARCore depth behavior, camera timestamps, negative-obstacle thresholds, audio routing, and haptics must be validated on the target phone matrix. The three-column ground-profile detector is a conservative candidate detector, not a guarantee of pothole or stair recognition.

## Release gate

Do not treat this debug APK as collision-proof or as a finished mobility aid. It is the Android Studio foundation and UI/safety orchestration milestone. Real-world safety validation and model integration are required before any production or public distribution.
