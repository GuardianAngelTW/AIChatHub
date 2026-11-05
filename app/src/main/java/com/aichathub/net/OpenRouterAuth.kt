package com.aichathub.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OpenRouter OAuth PKCE：使用者在瀏覽器登入 OpenRouter 帳號並授權，
 * 回呼到 aichathub://oauth/openrouter?code=...，再以 code 換取專屬 API Key。
 * 不需要使用者手動複製任何金鑰。
 */
object OpenRouterAuth {
    const val CALLBACK = "aichathub://oauth/openrouter"

    private fun b64url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun newVerifier(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let(::b64url)

    fun authUrl(verifier: String): String {
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "https://openrouter.ai/auth?callback_url=${URLEncoder.encode(CALLBACK, "UTF-8")}" +
            "&code_challenge=$challenge&code_challenge_method=S256"
    }

    suspend fun exchange(http: OkHttpClient, code: String, verifier: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://openrouter.ai/api/v1/auth/keys").post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("登入失敗 HTTP ${resp.code}：${text.take(300)}")
            Json.parseToJsonElement(text).jsonObject["key"]?.jsonPrimitive?.content
                ?: throw IOException("回應中沒有 key")
        }
    }
}
