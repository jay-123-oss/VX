# ARCore + TFLite spatial safety pipeline

The VX app now contains an offline-first ARCore and TensorFlow Lite pipeline. `ARCoreVisionManager` configures automatic focus and enables `Config.DepthMode.AUTOMATIC` when the device supports it. `CameraBackgroundRenderer` displays the live camera feed through an OpenGL ES external texture, while the existing depth-based safety corridor remains independent from object detection so missing models or uncertain depth never create a false safe state.

`ObjectDetectorHelper` loads `models/yolov8n-int8.tflite` and `models/coco_classes.txt` from the app assets. It converts camera bitmaps into float or quantized tensors, attempts GPU acceleration, falls back to NNAPI and then CPU, and returns normalized bounding boxes, labels, class IDs, and confidence values. A model binary is not committed; see `app/src/main/assets/models/README.md` for the supported output contracts and custom path configuration.

`SpatialFusionEngine` samples the ARCore 16-bit depth image at each detection center and maps the distance to the required zones: greater than 4 m is **Surakshit**, 2.5–4 m is **Chetaavni**, 1–2.5 m is **Savdhaan**, and less than 1 m is **Turant Ruke**. Unknown depth is treated as caution. Per-object trigger keys use class and quantized center coordinates, with a cooldown and higher-priority override to prevent repeated alerts while still escalating immediately.

`AlertController` provides offline Hindi Text-to-Speech and API-level compatible vibration patterns. `UIOverlayScreen.kt` renders normalized boxes and distance labels over the camera feed, a color-coded highest-threat status bar, and FPS, battery, CPU, GPU, and acceleration telemetry. `MainActivity` runs TFLite inference on the existing bounded perception executor at a controlled interval and sends fused detections to both the dashboard and alert controller.

## Gradle dependencies

The module now enables Jetpack Compose and adds the following dependency groups through the version catalog: Compose BOM, `activity-compose`, Compose UI, Material 3, TensorFlow Lite runtime, TensorFlow Lite GPU delegate, and TensorFlow Lite support. Existing ARCore, ONNX Runtime, coroutines, and LiteRT-LM dependencies are retained.

## Runtime behavior

The app dynamically requests the camera permission before creating an ARCore session. If ARCore depth is unavailable, the safety layer reports caution rather than assuming a clear path. If the optional TFLite model is missing, the existing ONNX on-demand search and depth-only safety path continue to function, while the Compose dashboard reports the detector as unavailable.
