# Phase 3 — Cloud Agent / Real-time Assistant architecture

## Purpose and boundary

The Cloud Agent is an **optional online assistant** for user-requested tasks that benefit from cloud reasoning, language generation, translation, route explanation, account-backed services, or remote model capacity. It is not a replacement for Reflex Shield, offline ONNX Search, or the offline Storyteller safety contract.

The assistant must be disabled by default until the user explicitly enables it and understands that selected data may leave the phone. When disabled, unavailable, offline, unvalidated, rate-limited, or failed, the application remains fully usable in offline mode. No safety state can depend on a cloud response.

> **Safety invariant:** A cloud response may add non-safety information or a supplementary caution, but it may never declare the path clear, downgrade a local warning, raise a local emergency, or delay the Reflex Shield loop.

## User-facing capabilities for Phase 3

The first Cloud Agent slice should support user-initiated conversational assistance rather than continuous surveillance. Suitable requests include explaining a detected object, translating a short user phrase, answering a general question, summarizing a locally generated scene description, or helping interpret a navigation instruction. Continuous camera streaming, automatic background uploads, and unattended cloud decisions are explicitly out of scope for this phase.

The local app should perform speech recognition where available, create a minimal request, and send only the data needed for the requested action. An image is uploaded only after an explicit action that requires visual context. The default request contains text and local metadata such as language and capability mode; it does not contain raw camera frames, depth maps, IMU traces, precise location, or device identifiers unless a later feature explicitly requires and discloses them.

## Trust and data-flow model

```text
User intent
    |
    v
Local consent + online toggle + metered-network policy
    |
    +--> disabled/offline --> OfflineAssistantFallback --> Hindi explanation
    |
    +--> enabled + validated HTTPS network
             |
             v
      CloudAssistantGateway (Android contract)
             |
             v
      User-owned secure relay / managed backend
             |
             +--> provider adapter and policy filter
             +--> response schema validation
             +--> redacted response
             |
             v
      Local Hindi TTS/UI, never Reflex Shield authority
```

Android should observe connectivity with `ConnectivityManager.NetworkCallback`, distinguish a validated internet connection from a merely configured network, and unregister the callback when the screen/lifecycle no longer needs it.[14] The app should not assume that Wi-Fi is unmetered or reliable. Cloud requests should use HTTPS only and keep cleartext traffic disabled; Android Network Security Configuration can enforce this and keep development overrides isolated from release builds.[15]

## Privacy contract

| Data category | Default behavior | Cloud transfer rule |
|---|---|---|
| User text command | Local processing first | Upload only for an enabled, user-initiated cloud request |
| Raw camera image | Never continuously uploaded | One latest JPEG only, explicit visual request, bounded dimensions/quality, not retained locally after handoff |
| Depth map | Local only | Never upload in Phase 3 |
| IMU/sensor stream | Local only | Never upload |
| Precise location | Local only | Never upload in Phase 3 |
| Raw microphone audio | Local only | Never upload; use local STT output if the user invokes voice input |
| Device identifiers | Not required | Do not send by default |
| Conversation history | Local session memory only | Send only the minimum context needed; provide a clear history-clear action |

These rules follow Android’s permission and privacy guidance: request minimal access, associate sensitive operations with explicit actions, be transparent, and minimize data for each task.[16]

## Three viable implementation options

| Approach | Tradeoffs | Cost | Setup complexity |
|---|---|---:|---:|
| Direct Android-to-provider API | Fastest prototype, but API keys would be exposed in the APK, provider lock-in is high, privacy controls are weak, and key rotation is difficult | Provider usage cost; higher security risk | Low initially, high for production hardening |
| Android → secure relay → provider | Keeps provider secrets server-side, enables rate limits, redaction, consent enforcement, audit controls, model switching, and a stable Android contract | Hosting plus provider usage; can start with a managed backend | Medium; recommended for production |
| Android → user-owned/local network assistant | Maximum data control and can support private deployments, but requires the user to operate a reachable service and may fail outside that network | User’s hosting/hardware and provider cost | High; lighter-weight alternative for advanced users |

The recommended production route is the **secure relay**. For the first coding milestone, only the Android gateway contract, offline fallback, request/response schema, consent state, network capability gate, and testable relay interface should be added. A provider-specific key or endpoint must not be invented or embedded. The relay deployment choice and provider are separate approvals.

## Request and response contract

The Android client should send a versioned request with an opaque request ID, user language, intent type, text, optional single JPEG, and a short timeout budget. It should not send safety-critical local state as authority. A relay response should be validated before any UI or speech output:

```json
{
  "schema_version": 1,
  "request_id": "opaque-client-id",
  "status": "ok",
  "language": "hi-IN",
  "answer": "...",
  "confidence": 0.0,
  "expires_at_ms": 0,
  "safety_authority": "none"
}
```

The only accepted `safety_authority` value in Phase 3 is `none`. Missing fields, expired responses, non-HTTPS responses, oversized payloads, unexpected status values, or malformed JSON are treated as cloud failure. The client speaks a Hindi fallback such as “ऑनलाइन सहायक उपलब्ध नहीं है, स्थानीय मोड जारी है।” It does not fabricate an answer.

## Runtime and resource policy

Cloud work is serialized and bounded. At most one assistant request is in flight; a newer user request may cancel a pending non-safety request. Timeouts should be short enough that a user is not left waiting indefinitely, and no cloud callback may run on the ARCore render thread. Images should be downscaled and JPEG-compressed before upload, with an explicit byte limit. Requests are disabled on metered networks by default unless the user changes the setting.

Cloud-agent thermal policy is subordinate to local safety. If the phone is hot, moving, low on battery, offline, or running a safety warning, the assistant is paused or rejected. No retry loop may compete with ARCore, ONNX search, or local TTS.

## Implementation gates

The Android gateway must first pass JVM tests for disabled mode, no-network fallback, timeout, malformed response, stale response, consent denial, metered-network rejection, request cancellation, and safety-authority rejection. The relay contract must then pass HTTPS-only, authentication, request-size, rate-limit, redaction, provider-timeout, and no-retention checks. Only after these gates may a real provider be selected and connected.

## Current milestone decision

This document authorizes the **architecture and model-independent Android boundary only**. It does not authorize continuous image upload, a provider API key, a cloud endpoint, background surveillance, or any cloud-controlled safety alert. The next implementation unit is `CloudAssistantGateway` plus `OfflineAssistantFallback` and a local consent/network policy object.

## References

[14]: https://developer.android.com/develop/connectivity/network-ops/reading-network-state "Android Read network state"

[15]: https://developer.android.com/privacy-and-security/security-config "Android Network Security Configuration"

[16]: https://developer.android.com/guide/topics/permissions/overview "Android Permissions and privacy best practices"
