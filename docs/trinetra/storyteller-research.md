# Storyteller VLM research findings

## Scope

This research evaluates an **offline Storyteller** for Trinetra. The Storyteller is an asynchronous visual-context layer. It must not replace the Reflex Shield, must not declare a path safe, and must not block ARCore frame processing.

## Candidate comparison

| Candidate | Offline Android path | Approximate published model size | License / restrictions | Hindi and safety suitability | Decision |
|---|---|---:|---|---|---|
| Gemma 3n E2B | LiteRT-LM multimodal model with Android Kotlin API; CPU/GPU/NPU backends are documented | 2,965 MB in the LiteRT-LM model table | Gemma Terms of Use. Redistribution requires the terms, restrictions, and notice text to accompany distribution | Multimodal and trained in more than 140 spoken languages, but the published device benchmarks are on newer flagship phones; too large to treat as a guaranteed 4 GB baseline | Enhanced-tier candidate only; requires physical-phone memory and thermal validation |
| SmolVLM-256M-Instruct | Model card includes ONNX weights, but Android integration requires a custom multi-session ONNX generation pipeline rather than a ready-made Android VLM package | 0.3B parameters; model card states one-image inference can run under 1 GB GPU RAM | Apache 2.0; the model card explicitly excludes high-stakes or critical decision-making | Very attractive for memory, but the model card lists English as its NLP language and warns against high-stakes use | Experimental context assistant; never the sole safety authority |
| Moondream2 | Model card demonstrates Transformers/custom-code inference and quantized variants, but no official LiteRT-LM Android path is documented | 2B parameters, BF16 model card | Apache 2.0; production use should pin the documented revision because the repository is updated | Has image querying, detection, and pointing skills, but resource/runtime integration risk is high for 4 GB phones | Research fallback, not the first Android integration |
| ONNX Runtime GenAI | Java API exists, but official documentation labels it Preview and says Java package publication is pending; Android use requires building from source | Depends on the selected multimodal ONNX model | Runtime and model licenses must be reviewed separately | Portable architecture, but not the lowest-risk Android v1 path | Keep as a future adapter, not the first Storyteller runtime |

## Recommended technical direction

The first implementation boundary should be a `StorytellerEngine` interface owned by the app, with a LiteRT-LM-backed implementation isolated behind it. LiteRT-LM currently documents a stable Android Kotlin API, multimodal `ImageFile`/`ImageBytes` content, CPU/GPU/NPU backends, background initialization, closeable engine/conversation resources, and explicit error handling. This makes it the cleanest runtime boundary for the Android-native project.

The first model to benchmark is Gemma 3n E2B because it has an official multimodal LiteRT-LM format and Android examples. It must be restricted to the Enhanced capability tier until a physical test matrix proves acceptable peak memory, startup time, one-image latency, sustained heat, and Hindi output quality. A 2.965 GB model file is not evidence that it will fit safely in a 4 GB phone's runtime memory; the model and native runtime coexist with ARCore, camera buffers, ONNX, audio, and the Android system.

SmolVLM-256M should be retained as the lower-memory experimental candidate. It has Apache 2.0 licensing and ONNX weights, but its model card identifies English NLP capability and explicitly says it is not intended for high-stakes or critical decision-making. It therefore cannot be promoted to a safety-critical role without targeted Hindi and hazard evaluation, and its custom ONNX generation pipeline must be benchmarked before adding it to the APK.

## Safety contract

The Storyteller returns a closed, validated result rather than free-form text:

```json
{
  "schema_version": 1,
  "hazard": "pothole | downward_stairs | uneven_ground | obstacle | crowd | none | unknown",
  "severity": "caution | warning | emergency | unknown",
  "region": "left | center | right | unknown",
  "confidence": 0.0,
  "evidence": "short model explanation",
  "captured_at_ns": 0,
  "expires_at_ns": 0
}
```

The parser must reject malformed JSON, clamp confidence to `[0, 1]`, reject unknown enum values into `unknown`, and expire every result. `evidence` is diagnostic text only and must never be used as a direct command.

The fusion policy is conservative:

| Condition | Reflex Shield result | Storyteller effect |
|---|---|---|
| Reliable close depth or confirmed negative obstacle | Warning/emergency | Storyteller cannot downgrade it |
| Depth unknown and VLM says hazard with sufficient validated confidence | Caution or warning according to the fixed policy table | May add a Hindi cross-check alert such as “दृश्य में सड़क असमान लग रही है, सावधानी रखें” |
| Depth reliable and clear but VLM says `unknown` | Existing Reflex Shield result | No downgrade and no “safe” claim from VLM |
| VLM says `none` | Existing Reflex Shield result | Never converts unknown depth to green |
| VLM timeout, crash, stale result, thermal suspension, or model unavailable | Existing Reflex Shield result | No fabricated result; show no VLM conclusion |
| Conflicting VLM hazard versus reliable depth | Safety state remains the more conservative state | Log the conflict for validation; do not hide it |

The Storyteller may raise a caution/warning, but it may never emit an emergency solely from an unvalidated free-form caption. Emergency remains reserved for Reflex Shield depth/TTC/negative-obstacle evidence or a separately validated deterministic hazard classifier.

## Scheduling contract

The model receives only the latest complete camera image; there is no queue of old frames. Normal scheduling is one snapshot every 7–10 seconds, never on the Reflex Shield callback. A faster diagnostic trigger may be considered only when depth is unknown for a sustained window, the phone is moving, or the scene changes materially. At most one VLM request may be in flight. Newer frames replace older pending work, and thermal policy can suspend Storyteller first.

The model is initialized lazily on a background thread. The UI, ARCore session, ONNX search, and safety executor must remain usable if initialization takes seconds or fails. The model engine and conversation must be explicitly closed on Activity destruction, and all native exceptions must become an unavailable result rather than an app crash.

## Acceptance gates before APK integration

1. **Runtime gate:** no network access, no crash on missing model, permission denial, provider failure, or Activity pause.
2. **Memory gate:** measure peak native and Java memory with ARCore, camera, ONNX, audio, and Storyteller simultaneously; a model is rejected if it causes system pressure or frame backlog on the minimum target phone.
3. **Latency gate:** measure image preprocessing, vision encoding, prompt evaluation, total response time, and thermal behavior over a sustained walking simulation. The model must never delay the Reflex Shield.
4. **Hindi gate:** evaluate short Hindi hazard prompts and Hindi TTS-facing labels; do not infer Hindi capability from a model's marketing description alone.
5. **Hazard gate:** create a real test set containing potholes, road patterns, downward stairs, low light, plain walls, shadows, and occlusions. Report false negatives and false positives separately; no claim of 99.9% accuracy is allowed without evidence.
6. **Safety gate:** test stale, malformed, contradictory, and low-confidence results. Every failure must remain at least as conservative as the corresponding Reflex Shield state.
7. **Distribution gate:** include model-card, runtime, and license notices before any public or commercial APK distribution.

## References

[1]: https://developers.google.com/edge/litert-lm/overview "LiteRT-LM Overview"

[2]: https://developers.google.com/edge/litert-lm/android "Get Started with LiteRT-LM on Android"

[3]: https://huggingface.co/google/gemma-3n-E2B-it-litert-lm "Gemma 3n E2B LiteRT-LM model card"

[4]: https://ai.google.dev/gemma/terms "Gemma Terms of Use"

[5]: https://huggingface.co/HuggingFaceTB/SmolVLM-256M-Instruct "SmolVLM-256M-Instruct model card"

[6]: https://huggingface.co/vikhyatk/moondream2 "Moondream2 model card"

[7]: https://onnxruntime.ai/docs/genai/api/java.html "ONNX Runtime GenAI Java API"

[8]: https://onnxruntime.ai/docs/tutorials/mobile/ "ONNX Runtime mobile deployment guide"
