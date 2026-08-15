# Trinetra Cloud Agent Relay Reference

This directory contains a **provider-neutral reference relay** for Phase 4. It validates the Android Cloud Agent contract and deliberately returns `503 provider adapter is not configured` for authenticated assistant requests. It does not call a cloud provider, store user prompts, or contain any secret.

The reference is not production-ready deployment code. Before exposing a relay to the Internet, add a real identity/token issuer, server-side secret storage, durable distributed rate limiting, structured security logging without request bodies, TLS termination with HSTS, provider timeout/circuit breaking, monitoring, and a selected provider adapter. Never place a provider API key in the Android APK or in the client request.

## Local validation

From this directory:

```bash
python3 -m unittest -v test_reference_relay.py
```

The current tests cover missing authentication, valid-request provider fallback, replayed request IDs, oversized JPEG input, and health reporting.

## Local development server

A developer may run the reference endpoint only for local contract testing:

```bash
export TRINETRA_RELAY_BEARER_TOKEN='local-development-token'
uvicorn reference_relay:app --host 127.0.0.1 --port 8080
```

Do not point a release APK at this cleartext local endpoint. The Android client accepts only `https://` relay URLs. For an HTTPS development environment, use a trusted local certificate through debug-only network-security configuration; do not weaken the release configuration.

## Contract

The full endpoint, schema, status-code, privacy, and deployment requirements are documented in `docs/trinetra/cloud-relay-contract.md`. The Android side is implemented by `CloudAssistantGateway.kt` and independently validates `safety_authority: none`, response expiry, matching request IDs, bounded answers, and clear-path claim rejection.
