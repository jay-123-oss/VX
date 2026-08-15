package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAssistantGatewayTest {
    private val freshRequest = CloudAssistantRequest(
        text = "मेरे लिए यह वाक्य समझाइए",
        createdAtMs = System.currentTimeMillis(),
        expiresAtMs = System.currentTimeMillis() + 10_000L
    )

    @Test
    fun policyRejectsCloudByDefault() {
        val reason = CloudAssistantPolicy().allow(
            freshRequest,
            CloudNetworkState(validatedInternet = true, metered = false)
        )
        assertEquals("ऑनलाइन सहायक बंद है", reason)
    }

    @Test
    fun policyRejectsMeteredNetworkUnlessAllowed() {
        val policy = CloudAssistantPolicy(
            onlineAssistantEnabled = true,
            userConsentGranted = true,
            allowMeteredNetwork = false
        )
        val reason = policy.allow(
            freshRequest,
            CloudNetworkState(validatedInternet = true, metered = true)
        )
        assertEquals("मोबाइल डेटा पर ऑनलाइन सहायक बंद है", reason)
    }

    @Test
    fun policyAllowsExplicitConsentOnValidatedUnmeteredNetwork() {
        val policy = CloudAssistantPolicy(
            onlineAssistantEnabled = true,
            userConsentGranted = true
        )
        assertEquals(
            null,
            policy.allow(freshRequest, CloudNetworkState(validatedInternet = true, metered = false))
        )
    }

    @Test
    fun offlineFallbackNeverClaimsCloudAnswer() {
        val response = OfflineAssistantFallback().execute(freshRequest)
        assertEquals(CloudAssistantStatus.DISABLED, response.status)
        assertTrue(response.answer.contains("स्थानीय मोड"))
        assertEquals(CloudSafetyAuthority.NONE, response.safetyAuthority)
    }

    @Test
    fun oversizedImageIsRejectedBeforeCloud() {
        val request = freshRequest.copy(imageJpeg = ByteArray(CloudAssistantRequest.MAX_IMAGE_BYTES + 1))
        assertFalse(request.isFresh())
    }

    @Test
    fun staleResponseIsNotDeliverable() {
        val response = CloudAssistantResponse(
            status = CloudAssistantStatus.OK,
            requestId = "request",
            answer = "उत्तर",
            confidence = 0.9f,
            languageTag = "hi-IN",
            expiresAtMs = System.currentTimeMillis() - 1L
        )
        assertFalse(response.isDeliverable())
    }

    @Test
    fun cloudResponseCannotClaimSafetyAuthority() {
        val response = CloudAssistantResponse(
            status = CloudAssistantStatus.OK,
            requestId = "request",
            answer = "रास्ता साफ है",
            confidence = 1f,
            languageTag = "hi-IN",
            expiresAtMs = System.currentTimeMillis() + 1_000L,
            safetyAuthority = CloudSafetyAuthority.NONE
        )
        assertFalse(response.isDeliverable())
    }
}
