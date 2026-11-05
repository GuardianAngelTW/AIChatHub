package com.aichathub.net

import com.aichathub.data.A2AAgent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Agent2Agent（A2A，Linux Foundation 開放協定）客戶端：
 * 讀取遠端代理的 Agent Card，並以 JSON-RPC message/send 委派任務。
 */
class A2AClient(http: OkHttpClient) {
    private val http = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class Card(val name: String, val description: String, val endpoint: String, val skills: List<String>)

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull

    fun fetchCard(url: String, token: String?): Card {
        val base = url.trim().trimEnd('/')
        val candidates = if (base.endsWith(".json")) listOf(base)
        else listOf("$base/.well-known/agent-card.json", "$base/.well-known/agent.json")
        var lastErr: Exception? = null
        for (c in candidates) {
            try {
                val rb = Request.Builder().url(c).header("Accept", "application/json")
                token?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
                http.newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                    val endpoint = o["url"].s()
                        ?: ((o["supportedInterfaces"] ?: o["additionalInterfaces"]) as? JsonArray)
                            ?.firstNotNullOfOrNull { (it as? JsonObject)?.get("url").s() }
                        ?: base
                    val skills = (o["skills"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name").s() }
                    return Card(o["name"].s() ?: "A2A Agent", o["description"].s().orEmpty(), endpoint, skills)
                }
            } catch (e: Exception) { lastErr = e }
        }
        throw IOException("讀不到 Agent Card：${lastErr?.message}")
    }

    private fun rpc(endpoint: String, token: String?, method: String, params: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString()); put("method", method); put("params", params)
        }
        val rb = Request.Builder().url(endpoint)
            .header("Accept", "application/json")
            .header("A2A-Version", "0.3")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        token?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        return http.newCall(rb.build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("A2A HTTP ${r.code}：${b.take(300)}")
            json.parseToJsonElement(b).jsonObject
        }
    }

    /** 送出訊息並等待結果；回傳代理輸出的文字 */
    fun send(agent: A2AAgent, token: String?, text: String): String {
        val endpoint = agent.endpoint.ifBlank { agent.url }
        val msgId = UUID.randomUUID().toString()
        var resp = rpc(endpoint, token, "message/send", buildJsonObject {
            putJsonObject("message") {
                put("role", "user"); put("kind", "message"); put("messageId", msgId)
                putJsonArray("parts") { addJsonObject { put("kind", "text"); put("text", text) } }
            }
            putJsonObject("configuration") { put("blocking", true) }
        })
        // 新版規格（v1.0）方法名稱改為 SendMessage
        if ((resp["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull == -32601) {
            resp = rpc(endpoint, token, "SendMessage", buildJsonObject {
                putJsonObject("message") {
                    put("role", "ROLE_USER"); put("messageId", msgId)
                    putJsonArray("parts") { addJsonObject { put("text", text) } }
                }
                putJsonObject("configuration") { put("blocking", true) }
            })
        }
        resp["error"]?.let { throw IOException("A2A 錯誤：$it") }
        val result = resp["result"] ?: return "（A2A 沒有回傳結果）"
        val texts = mutableListOf<String>()
        collectTexts(result, texts)
        val state = ((result as? JsonObject)?.get("status") as? JsonObject)?.get("state").s()
        return texts.distinct().joinToString("\n").ifBlank { "（A2A 代理狀態：${state ?: "未知"}，沒有文字輸出）" }
    }

    /** 從 Message / Task（status.message、artifacts）中收集所有文字 part；略過 history 避免重複 */
    private fun collectTexts(e: JsonElement, out: MutableList<String>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) ->
                when (k) {
                    "history" -> Unit
                    "parts" -> (v as? JsonArray).orEmpty().forEach { p -> (p as? JsonObject)?.get("text").s()?.let(out::add) }
                    else -> collectTexts(v, out)
                }
            }
            is JsonArray -> e.forEach { collectTexts(it, out) }
            else -> Unit
        }
    }
}
