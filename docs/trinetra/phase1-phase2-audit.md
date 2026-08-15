# Phase 1 and Phase 2 implementation audit

## Audit scope

This audit covers the repository at the current development head after adding the LiteRT-LM 0.16.0 adapter setup and the saturated-depth regression fix. It is a **static code and JVM-test audit**, not a physical-phone safety certification. No Android device with ARCore Depth API was connected during this check.

## Phase 1 — Core Safety status

| Component | Current implementation | Static result | Physical validation still required |
|---|---|---|---|
| ARCore lifecycle | One session, lifecycle-only resume/pause, permission check before session creation, safe shutdown | Passes source inspection | ARCore support, camera permission denial, pause/resume, and device-specific tracking |
| ARCore depth | `Config.DepthMode.AUTOMATIC` when supported; `LATEST_CAMERA_IMAGE`; camera and depth images are acquired from the same frame | Passes source inspection | Depth availability on each target phone, low texture, reflective surfaces, and low light |
| Timestamp/backlog control | Duplicate/stale ARCore timestamps rejected; one in-flight processing flag; render loop is dirty/rate-limited | Passes source inspection | Logcat confirmation that `RESOURCE_EXHAUSTED` backlog does not return while walking |
| Shared depth sampling | One duplicated depth-plane buffer per frame; 5x5 neighborhood averages; zero depth remains unknown | Passes source inspection | Coordinate alignment against camera preview on each device |
| Depth saturation | Values at or above 7,800 mm are not reliable clear-space evidence; all-saturated corridor remains CAUTION | Passes regression tests | Confirm repeated 7,867 mm logs never show green on a physical phone |
| Negative obstacle | Three vertical ground profiles use a tracked upward-facing horizontal plane and require two consecutive drop candidates | Passes after regression fix | Potholes, downward stairs, shadows, ramps, and false-positive testing |
| Negative obstacle saturation safety | Any zero or far-saturated profile point resets confirmation and returns `unknownGround`; saturated far readings cannot create a drop | Passes regression test | Confirm behavior with actual ARCore depth saturation patterns |
| Safety fusion | Unknown tracking/depth maps to CAUTION; negative drop maps to EMERGENCY; safety does not depend on ONNX or Storyteller | Passes source inspection and existing tests | Human-factors validation of warning timing, vibration, and audio |

### Important finding and fix

The audit found that the depth corridor already rejected all-saturated far readings, but the negative-obstacle profile path previously accepted a saturated value such as 7,867 mm as a normal positive depth. A profile containing a saturated far sample could therefore resemble a large ground discontinuity. The detector now rejects any profile containing zero or a value at or above 7,800 mm, resets its confirmation counter, and returns unknown ground instead of a drop. A unit test covers this case.

This fix makes the static safety logic conservative, but it does not prove pothole recognition. The detector is a ground-discontinuity candidate detector and must be evaluated against real potholes, downward stairs, ramps, road patterns, shadows, and missing-depth frames.

## Phase 2 — Offline Search status

| Component | Current implementation | Result |
|---|---|---|
| Model | Bundled `models/yolov8n_with_pre_post_processing.onnx` | Present; fixed-vocabulary YOLOv8n, not YOLO-World |
| Labels | Bundled `models/coco_classes.txt` | Loaded lazily from assets |
| Runtime | ONNX Runtime Android 1.29.0 plus ONNX Runtime Extensions 0.13.0 | Present and compiled |
| Trigger | User presses `वस्तु खोजें`; search is not continuously run | Matches the offline/thermal design |
| Provider | CPU baseline plus optional strict NNAPI with `CPU_DISABLED` and CPU fallback | Present; device-dependent |
| Detection filtering | Confidence threshold 0.35, maximum eight results, optional Hindi prompt alias filtering | Present |
| Distance | Best detection center is mapped into the same depth sampler used by safety | Present; distance may be unknown when depth is unavailable |
| Direction | Bounding-box center is converted to Hindi clock direction | Present |
| Hindi labels | Common aliases include person, car/truck/bus, chair, bottle, backpack, and cell phone | Present but incomplete; unmapped COCO labels remain English |
| Benchmark | CPU versus strict NNAPI benchmark exists; session creation time is excluded from average latency | Present after correction |

The current Search Engine is therefore genuinely offline and uses the bundled model, but it is not open-vocabulary. A request such as an arbitrary Hindi object name is not guaranteed to work unless the object exists in the fixed COCO classes and has an alias mapping. YOLO-World or another open-vocabulary model remains a separate model-selection and benchmark task.

## Storyteller LiteRT-LM setup

LiteRT-LM Android 0.16.0 is now declared as a Gradle dependency. The new `LiteRtLmStorytellerEngine` uses the official Kotlin API boundary and supports CPU, GPU, or NPU backend configuration, background initialization, image-byte messages, strict JSON-oriented prompts, result parsing, expiry, and lifecycle-safe close. `EnhancedStorytellerBenchmark` can compare CPU, GPU, and NPU backends when a developer supplies a local model path and a fresh `StorytellerFrame`.

The adapter is **not enabled by MainActivity**, and no model file is bundled. This is intentional: LiteRT-LM model size, Android native memory, Hindi output, thermal behavior, and hazard performance must be tested on an Enhanced-tier phone before activation. The existing button continues to report that the Storyteller model is unavailable.

Example developer-only benchmark call, to run off the main thread while stationary:

```kotlin
val nowNs = System.nanoTime()
val frame = StorytellerFrame(
    jpegBytes = jpegBytes,
    capturedAtNs = nowNs,
    expiresAtNs = nowNs + 10_000_000_000L
)
val results = EnhancedStorytellerBenchmark(context).run(
    modelPath = localLitertLmPath,
    frame = frame,
    runs = 1
)
```

## Verification performed

The JVM unit-test suite passed after the negative-obstacle saturation regression and LiteRT-LM adapter setup. The debug APK also assembled successfully with the new native LiteRT-LM dependency. Because no physical ARCore device was connected, the result should be described as **static implementation verified, physical behavior pending**.

## References

[1]: https://developers.google.com/ar/develop/java/depth/developer-guide "Google ARCore Depth API Developer Guide"

[2]: https://developers.google.com/edge/litert-lm/android "Get Started with LiteRT-LM on Android"

[3]: https://onnxruntime.ai/docs/tutorials/mobile/ "ONNX Runtime mobile deployment guide"

[4]: https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html "ONNX Runtime NNAPI Execution Provider"
