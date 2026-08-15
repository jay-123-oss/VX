package com.example.vx

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

/** Cloud is an optional assistant; it is never a source of safety authority. */
enum class CloudAssistantIntent {
    GENERAL_QUESTION,
    TRANSLATION,
    OBJECT_EXPLANATION,
    SCENE_SUMMARY,
    NAVIGATION_EXPLANATION
}

enum class CloudAssistantStatus {
    OK,
    DISABLED,
    OFFLINE,
    POLICY_REJECTED,
    TIMEOUT,
    MALFORMED_RESPONSE,
    SERVER_ERROR,
    UNAVAILABLE
}

enum class CloudSafetyAuthority { NONE }

data class CloudAssistantRequest(
    val text: String,
    val intent: CloudAssistantIntent = CloudAssistantIntent.GENERAL_QUESTION,
    val languageTag: String = "hi-IN",
    val imageJpeg: ByteArray? = null,
    val requestId: String = UUID.randomUUID().toString(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = createdAtMs + DEFAULT_REQUEST_TTL_MS
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        text.length <= MAX_TEXT_CHARS &&
            imageJpeg?.size?.let { it <= MAX_IMAGE_BYTES } != false &&
            nowMs in createdAtMs..expiresAtMs

    companion object {
        const val MAX_TEXT_CHARS = 2_000
        const val MAX_IMAGE_BYTES = 1_500_000
        const val DEFAULT_REQUEST_TTL_MS = 30_000L
    }
}

data class CloudAssistantResponse(
    val status: CloudAssistantStatus,
    val requestId: String,
    val answer: String,
    val confidence: Float,
    val languageTag: String,
    val expiresAtMs: Long,
    val safetyAuthority: CloudSafetyAuthority = CloudSafetyAuthority.NONE,
    val diagnosticHindi: String? = null
) {
    fun isDeliverable(nowMs: Long = System.currentTimeMillis()): Boolean =
        status == CloudAssistantStatus.OK &&
            requestId.isNotBlank() &&
            answer.isNotBlank() &&
            answer.length <= MAX_ANSWER_CHARS &&
            confidence in 0f..1f &&
            expiresAtMs > nowMs &&
            safetyAuthority == CloudSafetyAuthority.NONE &&
            DISALLOWED_SAFETY_CLAIMS.none { answer.contains(it, ignoreCase = true) }

    companion object {
        const val MAX_ANSWER_CHARS = 4_000
        private val DISALLOWED_SAFETY_CLAIMS = listOf(
            "रास्ता साफ",
            "मार्ग सुरक्षित",
            "path is clear",
            "safe path",
            "no obstacle"
        )
    }
}

data class CloudNetworkState(
    val validatedInternet: Boolean,
    val metered: Boolean,
    val captivePortal: Boolean = false
)

data class CloudAssistantPolicy(
    val onlineAssistantEnabled: Boolean = false,
    val userConsentGranted: Boolean = false,
    val allowMeteredNetwork: Boolean = false,
    val thermalAllowsOptionalWork: Boolean = true
) {
    fun allow(request: CloudAssistantRequest, network: CloudNetworkState): String? {
        if (!onlineAssistantEnabled) return "ऑनलाइन सहायक बंद है"
        if (!userConsentGranted) return "ऑनलाइन सहायक के लिए आपकी अनुमति आवश्यक है"
        if (!request.isFresh()) return "अनुरोध पुराना या बहुत बड़ा है"
        if (!network.validatedInternet || network.captivePortal) return "इंटरनेट उपलब्ध नहीं है"
        if (network.metered && !allowMeteredNetwork) return "मोबाइल डेटा पर ऑनलाइन सहायक बंद है"
        if (!thermalAllowsOptionalWork) return "फोन गर्म है, ऑनलाइन सहायक अभी रुका है"
        return null
    }
}

interface CloudAssistantGateway : AutoCloseable {
    /** Execute off the main thread. It must not be called from the ARCore render callback. */
    fun execute(request: CloudAssistantRequest): CloudAssistantResponse
}

class OfflineAssistantFallback : CloudAssistantGateway {
    override fun execute(request: CloudAssistantRequest): CloudAssistantResponse =
        CloudAssistantResponse(
            status = CloudAssistantStatus.DISABLED,
            requestId = request.requestId,
            answer = "ऑनलाइन सहायक उपलब्ध नहीं है, स्थानीय मोड जारी है।",
            confidence = 1f,
            languageTag = request.languageTag,
            expiresAtMs = System.currentTimeMillis() + 5_000L,
            diagnosticHindi = "ऑफलाइन सहायक fallback"
        )

    override fun close() = Unit
}

interface CloudAuthTokenProvider {
    /** Returns a short-lived relay token, never a provider API key. */
    fun bearerTokenOrNull(): String?
}

/**
 * Generic HTTPS relay client. The relay URL and token provider are supplied by the product layer;
 * no provider key is accepted or stored here. The caller owns consent and network policy checks.
 */
class HttpsRelayCloudAssistantGateway(
    private val relayUrl: String,
    private val authTokenProvider: CloudAuthTokenProvider? = null,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 8_000
) : CloudAssistantGateway {
    @Volatile private var closed = false

    override fun execute(request: CloudAssistantRequest): CloudAssistantResponse {
        if (closed) return failure(request, CloudAssistantStatus.UNAVAILABLE, "ऑनलाइन सहायक बंद है")
        if (!relayUrl.startsWith("https://", ignoreCase = true)) {
            return failure(request, CloudAssistantStatus.POLICY_REJECTED, "सुरक्षित HTTPS relay आवश्यक है")
        }
        if (!request.isFresh()) return failure(request, CloudAssistantStatus.POLICY_REJECTED, "अनुरोध पुराना या बहुत बड़ा है")

        val connection = runCatching { URL(relayUrl).openConnection() as HttpURLConnection }
            .getOrElse { return failure(request, CloudAssistantStatus.UNAVAILABLE, "relay उपलब्ध नहीं है") }
        return try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            authTokenProvider?.bearerTokenOrNull()?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            connection.outputStream.use { output ->
                output.write(request.toJson().toString().toByteArray(Charsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readBounded(it.bufferedReader(), MAX_RESPONSE_BYTES) }.orEmpty()
            if (statusCode !in 200..299) {
                failure(request, CloudAssistantStatus.SERVER_ERROR, "ऑनलाइन सेवा ने उत्तर नहीं दिया")
            } else {
                parseResponse(request, body)
            }
        } catch (_: java.net.SocketTimeoutException) {
            failure(request, CloudAssistantStatus.TIMEOUT, "ऑनलाइन सहायक का समय समाप्त हो गया")
        } catch (_: Exception) {
            failure(request, CloudAssistantStatus.UNAVAILABLE, "ऑनलाइन सहायक उपलब्ध नहीं है")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(request: CloudAssistantRequest, body: String): CloudAssistantResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return failure(request, CloudAssistantStatus.MALFORMED_RESPONSE, "ऑनलाइन उत्तर समझ में नहीं आया")
        val responseRequestId = json.optString("request_id", request.requestId)
        val status = json.optString("status", "")
        val answer = json.optString("answer", "").take(CloudAssistantResponse.MAX_ANSWER_CHARS)
        val confidence = json.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
        val expiresAt = json.optLong("expires_at_ms", 0L)
        val authority = json.optString("safety_authority", "none").lowercase()
        if (status != "ok" || responseRequestId != request.requestId || authority != "none" ||
            answer.isBlank() || expiresAt <= System.currentTimeMillis()) {
            return failure(request, CloudAssistantStatus.MALFORMED_RESPONSE, "ऑनलाइन उत्तर सुरक्षित नहीं है")
        }
        return CloudAssistantResponse(
            status = CloudAssistantStatus.OK,
            requestId = responseRequestId,
            answer = answer,
            confidence = confidence,
            languageTag = json.optString("language", request.languageTag),
            expiresAtMs = minOf(expiresAt, request.expiresAtMs),
            safetyAuthority = CloudSafetyAuthority.NONE
        )
    }

    override fun close() {
        closed = true
    }

    private fun failure(
        request: CloudAssistantRequest,
        status: CloudAssistantStatus,
        message: String
    ) = CloudAssistantResponse(
        status = status,
        requestId = request.requestId,
        answer = message,
        confidence = 0f,
        languageTag = request.languageTag,
        expiresAtMs = System.currentTimeMillis() + 5_000L,
        diagnosticHindi = message
    )

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        private fun readBounded(reader: BufferedReader, maxBytes: Int): String {
            val output = StringBuilder()
            var total = 0
            while (true) {
                val line = reader.readLine() ?: break
                total += line.toByteArray(Charsets.UTF_8).size + 1
                if (total > maxBytes) throw IllegalStateException("response too large")
                output.append(line).append('\n')
            }
            return output.toString()
        }
    }
}

private fun CloudAssistantRequest.toJson(): JSONObject = JSONObject().apply {
    put("schema_version", 1)
    put("request_id", requestId)
    put("language", languageTag)
    put("intent", intent.name.lowercase())
    put("text", text)
    put("created_at_ms", createdAtMs)
    put("expires_at_ms", expiresAtMs)
    imageJpeg?.let { put("image_jpeg_base64", Base64.encodeToString(it, Base64.NO_WRAP)) }
}
