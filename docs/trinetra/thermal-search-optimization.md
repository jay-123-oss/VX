# Thermal UI and dynamic offline Search optimization

## Safety boundary

These changes affect only the optional ONNX Search Engine and its UI. Reflex Shield continues to use the original ARCore camera/depth buffers, multi-point corridor, ground-profile arrays, and conservative unknown-depth rules. Search image downsampling never changes the depth image or safety distance calculation.

## Thermal UI behavior

`ThermalDutyManager.Policy` now exposes a `searchCooldownSeconds` value in addition to thermal status and headroom. MainActivity shows a Hindi status such as `फोन बहुत गर्म है, स्टोरीटेलर रुका है; सुरक्षा चालू है फिर जांच: 15 सेकंड बाद`. The countdown is updated at a bounded UI cadence rather than every depth frame. If the cooldown expires while the phone remains hot, the UI says that the cooldown ended but safety remains the priority; a new user request starts a fresh retry interval.

The cooldown is a conservative **search retry interval**, not a prediction of the phone’s exact physical cooling time. The app does not claim that the device is cool until Android thermal status/headroom allow the workload again.

## Dynamic Search profiles

| Profile | Maximum source dimension | JPEG quality | Minimum inference interval | Use |
|---|---:|---:|---:|---|
| `HIGH` | 1280 px | 82 | 1.5 s | Normal thermal state |
| `BALANCED` | 960 px | 74 | 2.5 s | Mild heat/headroom reduction |
| `LOW` | 640 px | 64 | 5 s | Moderate/severe thermal pressure |

The policy remains user-triggered; Search does not run continuously. The scheduler also blocks repeated button requests inside the profile interval. This reduces CPU work, JPEG memory, ONNX invocation frequency, and battery consumption. The full source image is not converted into a full-resolution YUV buffer for low profiles; downsampling happens while copying YUV into the target buffer.

Lower source resolution can reduce small-object recall. Therefore the normal profile remains high quality, and the reduced profiles are used only under thermal pressure. Device testing must compare recall and latency for bottle, person, vehicle, chair, and bicycle at each profile.

## Device test logging

Capture these tags while testing:

```text
ThermalDuty
WorkloadPolicy
OnnxObjectDetector
ReflexShield
```

Expected evidence includes `Search profile=...`, the profile dimensions/quality, thermal status/headroom, and no change to the ReflexShield depth values caused by Search profile selection.

## Limitations

This change cannot guarantee a fixed phone temperature because Android thermal status is device-specific and ambient conditions vary. It also cannot guarantee identical object accuracy after downsampling. The safety decision remains independent of ONNX; if Search is disabled or fails, Reflex Shield continues and unknown depth remains CAUTION.
