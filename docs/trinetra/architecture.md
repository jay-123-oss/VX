# Trinetra architecture decision record

## Approved product requirements

Trinetra will be an Android Studio native Kotlin application. It will support multiple Android devices, target phones with at least 4 GB RAM and 128 GB storage, use Hindi as the first language, include a UI, and keep the core processing local to the phone. The application will include Safety, Search, and Storyteller concepts in the first product direction, with runtime workload adaptation.

## Runtime boundaries

Safety is the highest-priority loop. It must not depend on the availability of YOLO, a VLM, speech recognition, or a network. Unknown depth, weak tracking, low light, transparent surfaces, and unsupported ARCore devices must resolve to caution rather than clear-space confidence.

Search is on demand and may be disabled on Basic devices or during thermal stress. Storyteller is low frequency and is allowed only when a validated local model is present and the device has sufficient capability. Audio ATC ensures that emergency haptics and beeps interrupt Search and Context narration.

## Technology selection

- Kotlin and Android Studio for the native application.
- Jetpack Compose for the Hindi-first UI and settings.
- CameraX with `STRATEGY_KEEP_ONLY_LATEST` for bounded frame acquisition.
- ARCore Depth API where the device reports support.
- Kotlin Coroutines and StateFlow for asynchronous state.
- ONNX Runtime Mobile for validated local search models.
- MLC-LLM or another validated mobile runtime for a compact quantized VLM.
- Android Text-to-Speech, ToneGenerator, and Vibrator APIs for feedback.
- DataStore or Room for local settings and opt-in diagnostics.

## Capability policy

The app selects a tier at runtime rather than assuming that a RAM/storage threshold implies ARCore or GPU support.

| Tier | Requirements | Behavior |
|---|---|---|
| Basic | Any supported install device | Conservative safety feedback, Hindi UI/audio, no expensive semantic workload |
| Standard | At least 4 GB RAM and ARCore availability | Safety, controlled search, reduced context workload |
| Enhanced | Higher memory, ARCore Depth, and validated model assets | Safety, search, and low-frequency Storyteller |

## Additional safety requirements

Negative obstacles are handled as a separate ground-profile signal. The Reflex Shield samples a bounded three-column corridor over the lower scene, estimates the expected depth rise along the ground, and requires temporal confirmation before escalating a likely pothole or downward stair to Emergency. Missing ground data remains Unknown/Caution rather than Clear.

An IMU motion manager uses filtered linear acceleration to classify the device as moving, still, or unknown. It does not claim to detect obstacles by itself. Its purpose is to make camera/depth failure more conservative while moving and to reduce expensive semantic workloads while still. The safety watchdog remains active in every motion state.

Audio feedback has a local stereo PCM fallback for left/right directional cues. Android Spatializer support can be added as an enhancement, but emergency feedback continues to use vibration and central audible cues so it does not depend on a particular earbud or device implementation.

Thermal policy reduces camera processing rate and suspends Storyteller/Search under thermal pressure while keeping Reflex Shield active. ONNX Runtime NNAPI/XNNPACK selection must be benchmarked per device; NNAPI does not guarantee NPU execution and unsupported operators may fall back to CPU.

## Testing gates

Before release, test with network disabled and measure safety alert latency, depth confidence transitions, false alarms, missed obstacles, negative-obstacle precision/recall, camera-blocked detection, TTS latency, directional beep latency, VLM latency, FPS, RAM, battery, and temperature. Test at least daylight, low light, crowds, stairs up and down, potholes, glass, textureless walls, parked two-wheelers, and moving obstacles.
