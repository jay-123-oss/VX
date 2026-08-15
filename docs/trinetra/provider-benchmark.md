# CPU versus optional NNAPI/NPU benchmark

`OnnxObjectDetector` now supports two providers:

- `CPU`: ORT CPU kernels with two intra-op threads and one inter-op thread.
- `NNAPI`: optional Android NNAPI provider with `CPU_DISABLED`. If the model cannot be assigned to the device accelerator, session creation or inference fails and the detector falls back to CPU.

The active provider is visible through `providerName()` and in Logcat under `OnnxObjectDetector`.

## Running the benchmark

Call the benchmark from a background thread with the same JPEG bytes for both providers:

```kotlin
lifecycleScope.launch(Dispatchers.Default) {
    val bytes = ImageFrameEncoder.toJpeg(cameraImage) ?: return@launch
    val results = objectDetector.benchmarkProviders(bytes, runs = 3)
    results.forEach { result ->
        Log.i(
            "OnnxBenchmark",
            "provider=${result.provider} available=${result.available} " +
                "averageMs=${result.averageMs} failed=${result.failedRuns} " +
                "message=${result.message}"
        )
    }
}
```

Do not run this benchmark in the Reflex Shield frame callback while walking. Run it while stationary, after the camera has been tracking for a few seconds. It is a developer diagnostic and does not change the active detector session.

## Acceptance rule

Select NNAPI only when it completes all benchmark runs, produces the same classes/confidences within the chosen tolerance as CPU, and improves latency or sustained thermal behavior. A fast provider that changes detections or causes repeated failures must be rejected. The app must retain CPU fallback on every device.

NNAPI is optional because Android documents NNAPI as deprecated from Android 15 onward. The current code keeps it behind a provider adapter so a future QNN, vendor accelerator, or newer runtime can be added without changing the Search Engine contract.
