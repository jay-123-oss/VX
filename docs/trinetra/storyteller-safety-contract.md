# Trinetra Storyteller safety contract

## Purpose

The Storyteller is an **offline visual-context cross-check**. It receives an occasional latest camera image and can add conservative context such as “the road appears uneven.” It is not the primary collision detector. The Reflex Shield remains the only continuously running safety authority for ARCore depth, time-to-collision, tracking validity, and deterministic negative-obstacle evidence.

> **Non-negotiable rule:** A Storyteller result may raise caution, but it may never turn unknown or unsafe depth into a green “path clear” state.

## Closed result schema

The model adapter must convert free-form model output into this validated result:

```json
{
  "schema_version": 1,
  "hazard": "pothole | downward_stairs | uneven_ground | obstacle | crowd | none | unknown",
  "severity": "caution | warning | emergency | unknown",
  "region": "left | center | right | unknown",
  "confidence": 0.0,
  "evidence": "short diagnostic explanation",
  "captured_at_ns": 0,
  "expires_at_ns": 0
}
```

The adapter rejects malformed output, clamps confidence to the range `[0, 1]`, maps unsupported enum values to `unknown`, limits the evidence string, and requires an expiry timestamp. The evidence sentence is never executed as a command and never becomes a direct safety decision.

## Fusion rules

| Reflex Shield state/evidence | Storyteller output | User-facing behavior |
|---|---|---|
| Confirmed emergency or warning from close reliable depth, TTC, or negative obstacle | Any result | Keep the stronger Reflex Shield state; Storyteller cannot downgrade it |
| Depth is unknown or tracking is unreliable | Valid hazard with validated confidence | Raise at most the fixed caution/warning level and speak a Hindi cross-check warning |
| Reliable depth reports clear space | `none` or `unknown` | Preserve the Reflex Shield state; Storyteller does not add “safe” confirmation |
| VLM reports `emergency` without deterministic safety evidence | Any | Downgrade the VLM-only emergency to a conservative warning/caution according to the fixed policy; do not issue an emergency command solely from caption text |
| VLM times out, crashes, is stale, is thermally suspended, or is unavailable | No result | Continue Reflex Shield only; do not fabricate a result |
| Valid VLM hazard conflicts with reliable depth | Hazard result | Keep the more conservative state and log the disagreement for validation |

Every result is ignored after `expires_at_ns`. If timestamps are missing or invalid, the result is treated as unavailable.

## Scheduling and resource policy

The Storyteller receives only the newest complete image. It must not maintain a queue of old images. Normal cadence is one image every **7–10 seconds**, with at most one inference in flight. A newer image replaces pending work. The inference runs outside the ARCore callback and cannot delay `Frame.close()`, `Session.update()`, or Reflex Shield evaluation.

The model is loaded lazily on a background thread. Thermal policy suspends Storyteller before reducing Reflex Shield safety. Activity pause, process shutdown, model initialization failure, native runtime error, and provider failure all return an unavailable result and leave the app in safety-only mode.

## Model and runtime boundary

The first runtime candidate is **LiteRT-LM through a small `StorytellerEngine` adapter**, because the current official Android guide documents a Kotlin API, multimodal image content, CPU/GPU/NPU backends, background initialization, closeable resources, and explicit error handling.[1] The first model candidate is Gemma 3n E2B for Enhanced-tier benchmarking because it has a documented multimodal LiteRT-LM model, but its published model size is approximately 2.965 GB and it must not be assumed suitable for a 4 GB phone without measurement.[2]

SmolVLM-256M remains a lower-memory experimental candidate because its model card documents ONNX weights and Apache 2.0 licensing, but also identifies English as its NLP language and explicitly excludes high-stakes or critical decision-making.[3] It cannot be used as the sole safety authority. Moondream2 is a useful research comparison, but its 2B BF16 model and custom-code deployment path are not the first choice for the minimum hardware profile.[4]

## Acceptance gates

Before the adapter is enabled in an APK, the model must pass physical-device tests for peak memory with ARCore and ONNX active, startup time, one-image latency, thermal behavior, Hindi prompt/output quality, pothole and downward-stair recall, low-light behavior, stale-result expiry, malformed-output handling, and conflict resolution. A model is rejected if it causes ARCore backlog, main-thread stalls, repeated crashes, or unsafe false-clear behavior.

No accuracy percentage is promised before a real hazard dataset and phone-matrix evaluation exist. This model remains an assistive cross-check, not a certified mobility aid.

## References

[1]: https://developers.google.com/edge/litert-lm/android "Get Started with LiteRT-LM on Android"

[2]: https://huggingface.co/google/gemma-3n-E2B-it-litert-lm "Gemma 3n E2B LiteRT-LM model card"

[3]: https://huggingface.co/HuggingFaceTB/SmolVLM-256M-Instruct "SmolVLM-256M-Instruct model card"

[4]: https://huggingface.co/vikhyatk/moondream2 "Moondream2 model card"
