package com.aichathub.net

import com.aichathub.data.McpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Model Context Protocol（MCP，開放標準）客戶端，使用 Streamable HTTP 傳輸。
 * 可連線任何遠端 MCP 伺服器（GitHub、Notion、資料庫、自建工具…），把其工具交給 Agent 使用。
 */
class McpClient(private val http: OkHttpClient) {
    data class McpTool(val name: String, val description: String, val inputSchema: JsonObject)

    private class SessionExpired : IOException("MCP session 已失效")

    private val json = Json { ignoreUnknownKeys = true }
    private val sessions = ConcurrentHashMap<String, String>()
    private val initialized = ConcurrentHashMap.newKeySet<String>()
    private val ids = AtomicInteger(1)
    private val protocol = "2025-06-18"

    private fun rpc(s: McpServer, token: String?, method: String, params: JsonObject?, notify: Boolean = false): JsonObject? {
        val id = ids.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            if (!notify) put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }
        val rb = Request.Builder().url(s.url.trim())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocol)
        token?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        sessions[s.id]?.let { rb.header("Mcp-Session-Id", it) }

        http.newCall(rb.build()).execute().use { r ->
            r.header("Mcp-Session-Id")?.let { sessions[s.id] = it }
            if (r.code == 404 && sessions.containsKey(s.id)) throw SessionExpired()
            if (!r.isSuccessful) throw IOException("MCP HTTP ${r.code}：${r.body?.string()?.take(300)}")
            if (notify) return null
            val ct = r.header("Content-Type").orEmpty()
            val msg: JsonObject = if (ct.contains("event-stream")) {
                val src = r.body?.source() ?: throw IOException("MCP 空回應")
                var found: JsonObject? = null
                while (found == null) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val o = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: continue
                    if ((o["id"] as? JsonPrimitive)?.intOrNull == id) found = o
                }
                found ?: throw IOException("MCP 沒有回應 $method")
            } else {
                json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            }
            msg["error"]?.let { throw IOException("MCP 錯誤：$it") }
            return msg["result"] as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    private fun ensureInit(s: McpServer, token: String?) {
        if (s.id in initialized) return
        sessions.remove(s.id)
        rpc(s, token, "initialize", buildJsonObject {
            put("protocolVersion", protocol)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "AIChatHub"); put("version", "1.0") }
        })
        runCatching { rpc(s, token, "notifications/initialized", null, notify = true) }
        initialized += s.id
    }

    private fun <T> withSession(s: McpServer, token: String?, block: () -> T): T = try {
        ensureInit(s, token); block()
    } catch (e: SessionExpired) {
        initialized.remove(s.id); sessions.remove(s.id)
        ensureInit(s, token); block()
    }

    fun listTools(s: McpServer, token: String?): List<McpTool> = withSession(s, token) {
        val res = rpc(s, token, "tools/list", null) ?: JsonObject(emptyMap())
        (res["tools"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            McpTool(
                o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                o["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object"); putJsonObject("properties") {} },
            )
        }
    }

    fun callTool(s: McpServer, token: String?, name: String, args: JsonObject): String = withSession(s, token) {
        val res = rpc(s, token, "tools/call", buildJsonObject { put("name", name); put("arguments", args) }) ?: JsonObject(emptyMap())
        val text = (res["content"] as? JsonArray).orEmpty().joinToString("\n") { c ->
            val o = c as? JsonObject ?: return@joinToString ""
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "resource" -> (o["resource"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                else -> "[${o["type"]?.jsonPrimitive?.contentOrNull} 內容]"
            }
        }.ifBlank { res["structuredContent"]?.toString().orEmpty() }
        if ((res["isError"] as? JsonPrimitive)?.contentOrNull == "true") "工具回報錯誤：$text" else text
    }

    fun reset(serverId: String) { initialized.remove(serverId); sessions.remove(serverId) }
}
