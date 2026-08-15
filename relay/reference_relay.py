"""Provider-neutral Phase 4 relay reference.

This file intentionally has no provider SDK and no default secret. It is a contract
reference and local validation harness, not a production deployment. Configure a real
server with a secret manager, trusted proxy, durable rate limiter, and provider adapter
before exposing it to the Internet.
"""

from __future__ import annotations

import base64
import binascii
import os
import threading
import time
from collections import defaultdict, deque
from typing import Annotated

from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_BODY_BYTES = 2_100_000
MAX_IMAGE_BYTES = 1_500_000
MAX_TEXT_CHARS = 2_000
MAX_REQUEST_TTL_MS = 30_000
RATE_LIMIT_COUNT = 10
RATE_LIMIT_WINDOW_SECONDS = 600

app = FastAPI(title="Trinetra Cloud Agent Relay", version="1")
_rate_lock = threading.Lock()
_rate_events: dict[str, deque[float]] = defaultdict(deque)
_seen_requests: dict[str, float] = {}


class AssistantRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: int = Field(ge=1, le=1)
    request_id: str = Field(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9._:-]+$")
    language: str = Field(min_length=2, max_length=16, pattern=r"^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
    intent: str = Field(pattern=r"^(general_question|translation|object_explanation|scene_summary|navigation_explanation)$")
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARS)
    created_at_ms: int = Field(gt=0)
    expires_at_ms: int = Field(gt=0)
    image_jpeg_base64: str | None = Field(default=None, max_length=2_100_000)

    @field_validator("image_jpeg_base64")
    @classmethod
    def validate_image(cls, value: str | None) -> str | None:
        if value is None:
            return None
        try:
            decoded = base64.b64decode(value, validate=True)
        except (binascii.Error, ValueError) as error:
            raise ValueError("image must be valid base64") from error
        if len(decoded) > MAX_IMAGE_BYTES:
            raise ValueError("image is too large")
        if not decoded.startswith(b"\xff\xd8"):
            raise ValueError("image must be JPEG")
        return value


class AssistantResponse(BaseModel):
    schema_version: int = 1
    request_id: str
    status: str
    language: str
    answer: str
    confidence: float = Field(ge=0.0, le=1.0)
    expires_at_ms: int
    safety_authority: str = "none"


def _error(status_code: int, request_id: str, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "schema_version": 1,
            "request_id": request_id,
            "status": "error",
            "message": message,
            "safety_authority": "none",
        },
        headers={"Cache-Control": "no-store"},
    )


def _auth_ok(authorization: str | None) -> bool:
    expected = os.environ.get("TRINETRA_RELAY_BEARER_TOKEN", "")
    return bool(expected) and authorization == f"Bearer {expected}"


def _request_key(request: Request, authorization: str | None) -> str:
    # Do not log or persist tokens. This key is an in-memory rate-limit bucket only.
    token_marker = authorization[-8:] if authorization else "anonymous"
    return f"{request.client.host if request.client else 'unknown'}:{token_marker}"


def _rate_allowed(key: str, now: float) -> bool:
    with _rate_lock:
        events = _rate_events[key]
        cutoff = now - RATE_LIMIT_WINDOW_SECONDS
        while events and events[0] <= cutoff:
            events.popleft()
        if len(events) >= RATE_LIMIT_COUNT:
            return False
        events.append(now)
        return True


def _fresh_and_not_replayed(request: AssistantRequest, now_ms: int) -> bool:
    if request.expires_at_ms <= request.created_at_ms:
        return False
    if request.expires_at_ms - request.created_at_ms > MAX_REQUEST_TTL_MS:
        return False
    if request.created_at_ms > now_ms + 5_000 or request.expires_at_ms < now_ms:
        return False
    with _rate_lock:
        for key, seen_at in list(_seen_requests.items()):
            if seen_at < time.time() - RATE_LIMIT_WINDOW_SECONDS:
                del _seen_requests[key]
        if request.request_id in _seen_requests:
            return False
        _seen_requests[request.request_id] = time.time()
    return True


@app.get("/v1/health")
def health() -> JSONResponse:
    return JSONResponse(
        {"status": "ok", "provider_configured": False},
        headers={"Cache-Control": "no-store"},
    )


@app.post("/v1/assistant/respond")
def respond(
    request: Request,
    payload: AssistantRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> JSONResponse:
    # The actual provider is intentionally absent. Never invent an assistant answer.
    if request.headers.get("content-type", "").split(";", 1)[0].lower() != "application/json":
        return _error(415, payload.request_id, "unsupported content type")
    content_length = request.headers.get("content-length")
    if content_length:
        try:
            declared_length = int(content_length)
        except ValueError:
            return _error(400, payload.request_id, "invalid content length")
        if declared_length < 0 or declared_length > MAX_BODY_BYTES:
            return _error(413, payload.request_id, "request too large")
    if not _auth_ok(authorization):
        return _error(401, payload.request_id, "authentication required")
    if not _fresh_and_not_replayed(payload, int(time.time() * 1000)):
        return _error(400, payload.request_id, "stale or replayed request")
    if not _rate_allowed(_request_key(request, authorization), time.time()):
        return _error(429, payload.request_id, "rate limit exceeded")
    return _error(503, payload.request_id, "provider adapter is not configured")
