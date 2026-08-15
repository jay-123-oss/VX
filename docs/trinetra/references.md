# Technical references

[1] Google ARCore Depth API Developer Guide: https://developers.google.com/ar/develop/java/depth/developer-guide

Key points used in the design: Depth is disabled by default and must be enabled after checking device support; depth frames may be unavailable when tracked feature points are missing; depth-image coordinates must be mapped carefully; a zero depth value means no depth data.

[2] Android Motion Sensors: https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion

Key points used in the design: accelerometer and gyroscope are hardware-based on Android devices, while derived sensors vary by device; raw accelerometer data requires filtering to remove gravity and noise; motion sensors should be treated as confidence inputs rather than stand-alone obstacle detectors.

[3] Android Thermal API: https://developer.android.com/games/optimize/adpf/thermal

Key points used in the design: thermal headroom/status is device-dependent; workloads should be reduced proactively; `getThermalHeadroom()` should not be polled too frequently; safety-critical work must remain prioritized while expensive workloads are reduced.

[4] ONNX Runtime NNAPI Execution Provider: https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html

Key points used in the design: NNAPI provides an interface to CPU, GPU, and neural accelerators on Android, but operator support and device execution are model-specific; CPU fallback and benchmark validation are required.

[5] Android NNAPI Migration Guide: https://developer.android.com/ndk/guides/neuralnetworks/migration-guide

Key point used in the implementation: Android documents NNAPI as deprecated in Android 15, so this project treats NNAPI as an optional benchmark/provider path with CPU fallback rather than a permanent production guarantee.

[6] Android Thermal API: https://developer.android.com/games/optimize/adpf/thermal

Key points used in the implementation: `PowerManager.getThermalHeadroom()` supports proactive workload control, should not be called more than once every 10 seconds, and should be interpreted together with thermal status because device mappings vary.
