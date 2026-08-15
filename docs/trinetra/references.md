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

[7] LiteRT-LM Overview: https://developers.google.com/edge/litert-lm/overview

Key points used in the Storyteller design: LiteRT-LM documents Android support, multimodal models, CPU/GPU/NPU backends, and a Kotlin API.

[8] LiteRT-LM Android Kotlin Guide: https://developers.google.com/edge/litert-lm/android

Key points used in the Storyteller design: Android integration uses a Kotlin API, image content, background engine initialization, closeable resources, backend selection, and explicit error handling.

[9] Gemma 3n E2B LiteRT-LM Model Card: https://huggingface.co/google/gemma-3n-E2B-it-litert-lm

Key points used in the Storyteller design: Gemma 3n E2B supports image input in LiteRT-LM format and reports multilingual training, but the model size and published benchmark devices require physical validation for the 4 GB target.

[10] Gemma Terms of Use: https://ai.google.dev/gemma/terms

Key points used in the Storyteller design: Gemma distribution requires the terms, use restrictions, and notice obligations to be passed to recipients; model terms must be reviewed before public or commercial APK distribution.

[11] SmolVLM-256M-Instruct Model Card: https://huggingface.co/HuggingFaceTB/SmolVLM-256M-Instruct

Key points used in the Storyteller design: the model card documents image-text input, ONNX weights, Apache 2.0 licensing, English NLP capability, and an explicit warning against high-stakes or critical decision-making.

[12] Moondream2 Model Card: https://huggingface.co/vikhyatk/moondream2

Key points used in the Storyteller design: Moondream2 provides image querying, detection, and pointing skills under Apache 2.0, but its 2B BF16/custom-code path is not the first 4 GB Android integration choice.

[13] ONNX Runtime GenAI Java API: https://onnxruntime.ai/docs/genai/api/java.html

Key points used in the Storyteller design: the Java API is documented as preview and the package publication is pending, so it is kept as a future adapter rather than the first Android runtime.

[14] Android ConnectivityManager NetworkCallback: https://developer.android.com/develop/connectivity/network-ops/reading-network-state

Key points used in the Cloud Agent design: register a default NetworkCallback for live connectivity changes instead of polling; distinguish INTERNET from VALIDATED; do not assume Wi-Fi is unmetered; unregister the callback when no longer needed; and keep callback work off the connectivity thread.

[15] Android Network Security Configuration: https://developer.android.com/privacy-and-security/security-config

Key points used in the Cloud Agent design: use HTTPS/TLS, keep cleartext traffic disabled, configure trust and debug overrides declaratively, and avoid embedding insecure development exceptions in the release configuration.

[16] Android Permissions Overview and Best Practices: https://developer.android.com/guide/topics/permissions/overview

Key points used in the Cloud Agent design: request the minimum permissions, associate sensitive access with an explicit user action, be transparent about camera/microphone use, and minimize data shared for each task.
