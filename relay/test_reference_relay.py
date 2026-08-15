import base64
import os
import time
import unittest

from fastapi.testclient import TestClient

os.environ["TRINETRA_RELAY_BEARER_TOKEN"] = "test-relay-token"

from reference_relay import app, _rate_events, _seen_requests


class ReferenceRelayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def setUp(self):
        _rate_events.clear()
        _seen_requests.clear()

    def request_payload(self, request_id="req-0001"):
        now = int(time.time() * 1000)
        return {
            "schema_version": 1,
            "request_id": request_id,
            "language": "hi-IN",
            "intent": "general_question",
            "text": "समझाइए",
            "created_at_ms": now,
            "expires_at_ms": now + 10_000,
        }

    def headers(self):
        return {
            "Authorization": "Bearer test-relay-token",
            "Content-Type": "application/json",
        }

    def test_missing_auth_is_rejected(self):
        response = self.client.post(
            "/v1/assistant/respond",
            json=self.request_payload(),
        )
        self.assertEqual(response.status_code, 401)

    def test_valid_request_stops_without_provider(self):
        response = self.client.post(
            "/v1/assistant/respond",
            json=self.request_payload(),
            headers=self.headers(),
        )
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["safety_authority"], "none")

    def test_replay_is_rejected(self):
        payload = self.request_payload()
        first = self.client.post("/v1/assistant/respond", json=payload, headers=self.headers())
        second = self.client.post("/v1/assistant/respond", json=payload, headers=self.headers())
        self.assertEqual(first.status_code, 503)
        self.assertEqual(second.status_code, 400)

    def test_oversized_image_is_rejected_by_schema(self):
        payload = self.request_payload("req-image")
        payload["image_jpeg_base64"] = base64.b64encode(b"\xff\xd8" + b"x" * 1_500_001).decode()
        response = self.client.post("/v1/assistant/respond", json=payload, headers=self.headers())
        self.assertEqual(response.status_code, 422)

    def test_health_does_not_claim_provider_configured(self):
        response = self.client.get("/v1/health")
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.json()["provider_configured"])


if __name__ == "__main__":
    unittest.main()
