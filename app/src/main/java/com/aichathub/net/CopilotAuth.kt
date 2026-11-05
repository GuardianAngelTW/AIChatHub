package com.aichathub.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * GitHub Copilot 登入（GitHub OAuth Device Flow）：
 * 1. 取得 user_code → 使用者到 github.com/login/device 輸入並授權
 * 2. 輪詢取得 GitHub token（存入 Keystore）
 * 3. 每次呼叫前用 GitHub token 換短效 Copilot token（自動快取、到期前更新）
 *
 * 注意：Copilot Chat API 非公開文件介面，做法與 OpenCode 等開源客戶端相同，
 * 需要有效的 Copilot 訂閱，GitHub 可能隨時變更。
 */
object CopilotAuth {
    /** 開源 Copilot 客戶端（OpenCode、CopilotChat.nvim 等）共用的 GitHub OAuth App client id */
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
    const val API_BASE = "https://api.githubcopilot.com"

    val HEADERS = mapOf(
        "Editor-Version" to "vscode/1.99.0",
        "Editor-Plugin-Version" to "copilot-chat/0.26.0",
        "Copilot-Integration-Id" to "vscode-chat",
        "User-Agent" to "AIChatHub/1.0",
    )

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val interval: Int, val expiresIn: Int)

    private val json = Json { ignoreUnknownKeys = true }
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    suspend fun requestDeviceCode(http: OkHttpClient): DeviceCode = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("client_id", CLIENT_ID).add("scope", "read:user").build())
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("GitHub HTTP ${r.code}：${body.take(300)}")
            val o = json.parseToJsonElement(body).jsonObject
            DeviceCode(
                o.s("device_code") ?: throw IOException("缺少 device_code"),
                o.s("user_code") ?: throw IOException("缺少 user_code"),
                o.s("verification_uri") ?: "https://github.com/login/device",
                o["interval"]?.jsonPrimitive?.intOrNull ?: 5,
                o["expires_in"]?.jsonPrimitive?.intOrNull ?: 900,
            )
        }
    }

    /** 輪詢直到使用者授權，回傳 GitHub access token */
    suspend fun pollToken(http: OkHttpClient, dc: DeviceCode): String = withContext(Dispatchers.IO) {
        var interval = dc.interval
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("device_code", dc.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build())
                .build()
            val o = http.newCall(req).execute().use { json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject }
            o.s("access_token")?.let { return@withContext it }
            when (o.s("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "expired_token" -> throw IOException("驗證碼已過期，請重新登入")
                "access_denied" -> throw IOException("已拒絕授權")
                else -> throw IOException("登入失敗：${o.s("error_description") ?: o}")
            }
        }
        throw IOException("登入逾時，請重新登入")
    }

    private var cached: Pair<String, Long>? = null // copilot token 與到期時間（秒）
    private var cachedFor: String? = null

    /** 以 GitHub token 換取 Copilot API token（阻塞呼叫，請在 IO 執行緒） */
    @Synchronized
    fun copilotToken(http: OkHttpClient, githubToken: String): String {
        val now = System.currentTimeMillis() / 1000
        cached?.let { (t, exp) -> if (cachedFor == githubToken && exp - 120 > now) return t }
        val req = Request.Builder().url("https://api.github.com/copilot_internal/v2/token")
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/json")
            .apply { HEADERS.forEach { (k, v) -> header(k, v) } }
            .get().build()
        return http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403 || r.code == 404)
                throw IOException("無法取得 Copilot 權限（HTTP ${r.code}）：請確認帳號有 Copilot 訂閱，或重新登入")
            if (!r.isSuccessful) throw IOException("Copilot HTTP ${r.code}：${body.take(300)}")
            val o = json.parseToJsonElement(body).jsonObject
            val t = o.s("token") ?: throw IOException("Copilot 回應缺少 token")
            cached = t to (o["expires_at"]?.jsonPrimitive?.longOrNull ?: (now + 1500))
            cachedFor = githubToken
            t
        }
    }
}
