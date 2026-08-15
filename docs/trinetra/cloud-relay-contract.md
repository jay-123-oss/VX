# Phase 4 — Secure Cloud Agent relay contract

## Scope

This document defines a provider-neutral HTTPS relay for Trinetra’s optional Cloud Agent. It is a contract and security boundary, not a deployed provider integration. The relay keeps third-party model credentials server-side, validates every request and response, and returns only bounded assistant text. The Android client remains functional when the relay is unavailable.

The relay must never receive or make decisions from Reflex Shield authority. It must not receive continuous camera frames, depth maps, IMU streams, raw microphone audio, precise location, or device identifiers in this phase.

## Endpoint inventory

| Method | Endpoint | Client use | Authentication | Retention |
|---|---|---|---|---|
| `POST` | `/v1/assistant/respond` | One user-initiated request | Short-lived relay token in `Authorization` header | No request/response body retention |
| `GET` | `/v1/health` | Operational health only | No user data; protect from abuse | No body logging |
| `GET` | `/v1/config` | Optional non-secret capability discovery | Short-lived relay token | No user data |
| Any other method/path | Rejected | Not supported | N/A | No body logging |

The production relay must expose HTTPS only. Authentication tokens must never appear in URLs or query strings. The relay should validate the token issuer, audience, expiry, not-before time, signature, and revocation state when JWTs are used. If a user-account system is not selected, the relay may begin with a short-lived app-session token issued by a separate authenticated enrollment flow; a static API key embedded in the APK is not acceptable.

## Request schema

`POST /v1/assistant/respond` accepts exactly `application/json` with a strict maximum body size of 2,100,000 bytes. The Android client’s current image limit is 1,500,000 bytes before base64 expansion; the relay must enforce a smaller decoded-image limit after parsing.

```json
{
  "schema_version": 1,
  "request_id": "opaque-client-generated-id",
  "language": "hi-IN",
  "intent": "general_question",
  "text": "मेरे लिए यह वाक्य समझाइए",
  "created_at_ms": 1770000000000,
  "expires_at_ms": 1770000030000,
  "image_jpeg_base64": "optional"
}
```

The relay validates that `request_id` is bounded and unique within a short replay window, `language` is an allowlisted BCP-47 tag, `intent` is one of the Android enum values, `text` is non-empty and at most 2,000 Unicode characters, timestamps are close to server time and within a 30-second request TTL, and the optional image is JPEG, within the decoded dimension/byte limit, and present only for intents that explicitly require visual context. Unknown JSON properties should be rejected or ignored consistently; the first release should reject them to expose client/server drift early.

The relay must not accept arbitrary provider URLs, arbitrary model names, tool definitions, system prompts, file paths, or code execution instructions from the Android client. The provider destination and model are fixed server configuration, preventing server-side request forgery and unapproved model routing.

## Response schema

Successful responses use `200 OK` and `application/json`:

```json
{
  "schema_version": 1,
  "request_id": "same-opaque-client-generated-id",
  "status": "ok",
  "language": "hi-IN",
  "answer": "यह वाक्य ...",
  "confidence": 0.74,
  "expires_at_ms": 1770000025000,
  "safety_authority": "none"
}
```

The relay must return `safety_authority: "none"` for every Phase 4 response. It must not return a green/clear-path decision, emergency instruction, collision distance, or any field that appears to compete with Reflex Shield. If the provider returns such content, the relay either redacts it into a generic informational response or rejects the response. The Android client independently revalidates the same invariant.

The answer is limited to 4,000 characters, confidence is a number from 0 to 1, the response request ID must match, and `expires_at_ms` must be no later than the request expiry. Provider chain-of-thought, hidden prompts, tool traces, raw provider errors, stack traces, and upstream tokens must never be returned.

## HTTP rejection contract

| Status | Condition | Client behavior |
|---:|---|---|
| `400` | Invalid JSON, schema version, timestamp, enum, or request ID | Do not retry automatically; use local fallback |
| `401` | Missing/invalid/expired token | Disable cloud session and request re-consent/login |
| `403` | Valid identity but feature or policy not allowed | Use local fallback and explain permission/policy |
| `413` | Body or decoded image too large | Do not retry unchanged request |
| `415` | Unsupported content type or image type | Use local fallback |
| `422` | Valid JSON but disallowed intent/content | Use local fallback; no provider call |
| `429` | Per-user/device/IP rate limit exceeded | Back off; do not compete with safety loop |
| `408`/`504` | Relay/provider timeout | Use local fallback; no indefinite retry |
| `502` | Provider unavailable or invalid provider response | Use local fallback and generic status |
| `503` | Relay disabled, overloaded, or under maintenance | Use local fallback |

## Security and privacy controls

The relay applies authentication and authorization at the endpoint, validates content type and size before parsing expensive fields, and uses a per-token plus per-device coarse rate limit. Suggested initial limits are one in-flight request per session, ten requests per ten minutes per user, and a global concurrency cap selected from actual hosting capacity. These are starting safeguards, not final capacity guarantees.

The relay uses an allowlisted fixed provider destination. It does not fetch user-supplied URLs and does not forward arbitrary headers. Provider API keys are read only from server-side secret storage, never from Android payloads, logs, response bodies, or source control. Logs contain request ID, timestamp, status, latency bucket, and rejection reason only; they do not contain text, image bytes, authorization headers, provider prompts, or provider responses. Request/response body persistence is disabled. Operational telemetry must use aggregated counters with a short retention period.

The relay returns `Cache-Control: no-store`, `Content-Type: application/json`, and generic error bodies. It should use HSTS at the domain/edge layer. CORS is disabled unless a browser client is explicitly added; the Android client does not need CORS. Administrative and health management surfaces are separated from the user endpoint and must not expose provider configuration.

These controls address broken authentication, unrestricted resource consumption, security misconfiguration, unsafe third-party API consumption, SSRF, content-type confusion, oversized requests, and secrets leakage risks identified by OWASP.[17] [18]

## Provider adapter boundary

```text
Android CloudAssistantGateway
          |
          | HTTPS JSON + short-lived relay token
          v
Relay request validator
          |
          +--> consent/auth/policy checks
          +--> size/type/TTL/rate-limit checks
          +--> redaction and prompt construction
          +--> fixed ProviderAdapter (server secret only)
          +--> response schema/safety filter
          v
Bounded JSON response: safety_authority = none
```

`ProviderAdapter` is intentionally not specified in this milestone. The server may later connect to one selected provider or a self-hosted model, but the provider must be replaceable without changing the Android contract. The relay must not expose provider model names or provider-specific error details to the phone.

## Deployment choices

A managed backend is the default for this relay because it can provide HTTPS, secret storage, logs, rate limiting, and a small stateless request handler without exposing a server key in the Android app. A user-owned server is a valid alternative when the user requires data residency or a private provider, but it adds TLS, patching, monitoring, backup, and availability responsibilities. A persistent always-on host is not required for the initial request/response relay; a stateless HTTPS endpoint is sufficient. A realtime streaming channel should be considered only after request/response reliability and privacy gates pass.

No deployment is performed in this milestone because the provider, authentication issuer, domain, retention policy, and hosting account have not been selected. The next approval must specify the provider-neutral relay host and authentication enrollment flow, not a provider API key pasted into the Android project.

## Validation gates

The relay is ready for a real provider only after tests prove rejection of HTTP, wrong method, invalid token, expired token, replayed request ID, oversized JSON, oversized image, wrong content type, unknown intent, stale timestamps, arbitrary provider URL, unsafe response authority, oversized answer, provider timeout, provider error, and rate-limit overflow. Integration tests must also confirm that the Android `CloudAssistantResponse` parser accepts the success schema and falls back for every rejection class.

## References

[17]: https://owasp.org/API-Security/editions/2023/en/0x11-t10/ "OWASP Top 10 API Security Risks 2023"

[18]: https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html "OWASP REST Security Cheat Sheet"

[19]: https://developer.android.com/privacy-and-security/security-config "Android Network Security Configuration"
