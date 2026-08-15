# Fix YOLO Inference Pipeline: Pre-processing & Post-processing

The goal is to fix the "Trinetra AI Sentinel" object detection pipeline by adding optimized C++ pre-processing (YUV to RGB, Resize, Normalize) and Kotlin post-processing (Output Parsing, NMS).

## Proposed Changes

### Native JNI Layer (C++)

#### [MODIFY] [native-lib.cpp](file:///C:/Users/jayde/AndroidStudioProjects/VX2/app/src/main/cpp/native-lib.cpp)
- Implement `preprocessFrame` function.
- Add an optimized YUV420 to RGB conversion and resizing (bilinear interpolation) logic.
- Support normalization (Float32 / 255.0).
- Directly populate the provided `java.nio.ByteBuffer`.

### Java/Kotlin Bridge

#### [MODIFY] [NativeBufferBridge.kt](file:///C:/Users/jayde/AndroidStudioProjects/VX2/app/src/main/java/com/example/vx/NativeBufferBridge.kt)
- Add `external fun preprocessFrame` declaration.

### ML Inference Logic (Kotlin)

#### [MODIFY] [InferencePipeline.kt](file:///C:/Users/jayde/AndroidStudioProjects/VX2/app/src/main/java/com/example/vx/InferencePipeline.kt)
- Define `DetectionResult` data class.
- Update `executeInferenceCycle` to:
    - Prepare input and output buffers.
    - Call native pre-processing.
    - Run TFLite inference.
    - Parse the output tensor.
    - Apply Non-Maximum Suppression (NMS).
- Implement `applyNMS` helper function.

## Verification Plan

### Automated Tests
- Build the project to ensure JNI signatures match.
- Check logs for "Inference successful" and detection counts.

### Manual Verification
- Deploy to device.
- Observe Logcat for `DetectionResult` logs.
- Verify that detections are now appearing where they were previously absent.
