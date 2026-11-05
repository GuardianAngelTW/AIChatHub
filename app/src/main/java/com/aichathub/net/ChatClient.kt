package com.aichathub.net

import com.aichathub.data.ApiType
import com.aichathub.data.AuthKind
import com.aichathub.data.ChatMessage
import com.aichathub.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 統一的對話客戶端：把三種協定（OpenAI 相容 / Anthropic / Gemini）
 * 都轉成同一個「文字增量串流」Flow<String>。
 */
class ChatClient(
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // 本地大模型首字可能很慢
        .build(),
) {
    internal val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    internal fun base(p: ProviderConfig) = p.baseUrl.trim().trimEnd('/')

    internal fun Request.Builder.auth(p: ProviderConfig, key: String?): Request.Builder = apply {
        var k = key?.takeIf { it.isNotBlank() } ?: return@apply
        if (p.auth == AuthKind.OAUTH_GITHUB_COPILOT) k = CopilotAuth.copilotToken(http, k) // GitHub token → Copilot token
        when (p.type) {
            ApiType.OPENAI -> header("Authorization", "Bearer $k")
            ApiType.ANTHROPIC -> header("x-api-key", k)
            ApiType.GEMINI -> header("x-goog-api-key", k)
            // OpenCode Server 用 HTTP Basic；可填「密碼」或「帳號:密碼」，帳號預設 opencode
            ApiType.OPENCODE -> {
                val (u, pw) = if (':' in k) k.substringBefore(':') to k.substringAfter(':') else "opencode" to k
                header("Authorization", Credentials.basic(u, pw))
            }
        }
    }

    internal fun Request.Builder.common(p: ProviderConfig): Request.Builder = apply {
        if (p.type == ApiType.ANTHROPIC) header("anthropic-version", "2023-06-01")
        if (p.auth == AuthKind.OAUTH_GITHUB_COPILOT) CopilotAuth.HEADERS.forEach { (k, v) -> header(k, v) }
        if (p.baseUrl.contains("openrouter.ai")) {
            header("HTTP-Referer", "https://github.com/aichathub/android")
            header("X-Title", "AI Chat Hub")
        }
    }

    // ---------------- 對話（串流） ----------------

    fun stream(
        p: ProviderConfig,
        key: String?,
        model: String,
        system: String,
        history: List<ChatMessage>,
        remoteSession: String? = null,
        onRemoteSession: (String) -> Unit = {},
    ): Flow<String> = flow {
        if (p.type == ApiType.OPENCODE) {
            emit(openCodeSend(p, key, model, system, history.last().content, remoteSession, onRemoteSession))
            return@flow
        }
        val req = when (p.type) {
            ApiType.OPENAI -> openAiRequest(p, model, system, history)
            ApiType.ANTHROPIC -> anthropicRequest(p, model, system, history)
            ApiType.GEMINI -> geminiRequest(p, model, system, history)
            ApiType.OPENCODE -> error("unreachable")
        }.auth(p, key).common(p).header("Accept", "text/event-stream").build()

        val call = http.newCall(req)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() } // 按「停止」時中斷連線
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}：${resp.body?.string()?.take(500)}")
            val source = resp.body?.source() ?: throw IOException("空的回應")
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["error"]?.takeIf { it !is JsonNull }?.let { throw IOException("服務端錯誤：$it") }
                val text = when (p.type) {
                    ApiType.OPENAI -> obj["choices"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("delta")?.jsonObject?.get("content")?.str()
                    ApiType.ANTHROPIC -> when (obj["type"]?.str()) {
                        "content_block_delta" -> obj["delta"]?.jsonObject?.get("text")?.str()
                        "error" -> throw IOException("Anthropic 錯誤：${obj["error"]}")
                        else -> null
                    }
                    ApiType.GEMINI -> obj["candidates"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                        ?.joinToString("") { it.jsonObject["text"]?.str().orEmpty() }
                    ApiType.OPENCODE -> null
                }
                if (!text.isNullOrEmpty()) emit(text)
            }
        }
    }.flowOn(Dispatchers.IO)

    internal fun post(url: String, body: JsonObject) =
        Request.Builder().url(url).post(body.toString().toRequestBody(jsonType))

    private fun openAiRequest(p: ProviderConfig, model: String, system: String, history: List<ChatMessage>) =
        post("${base(p)}/chat/completions", buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                if (system.isNotBlank()) addJsonObject { put("role", "system"); put("content", system) }
                history.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
            }
        })

    private fun anthropicRequest(p: ProviderConfig, model: String, system: String, history: List<ChatMessage>) =
        post("${base(p)}/messages", buildJsonObject {
            put("model", model)
            put("max_tokens", 8192)
            put("stream", true)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                history.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
            }
        })

    private fun geminiRequest(p: ProviderConfig, model: String, system: String, history: List<ChatMessage>) =
        post("${base(p)}/models/${model.removePrefix("models/")}:streamGenerateContent?alt=sse", buildJsonObject {
            if (system.isNotBlank()) putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                history.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        putJsonArray("parts") { addJsonObject { put("text", m.content) } }
                    }
                }
            }
        })

    // ---------------- OpenCode Server ----------------
    // 文件：https://opencode.ai/docs/server/ （伺服器在 /doc 提供完整 OpenAPI 規格）
    //   POST /session                 建立 session → { id, ... }
    //   POST /session/:id/message     { parts:[{type:"text",text}], model:{providerID,modelID}, system? }
    //                                 等待 agent 完成後回傳 { info, parts:[...] }
    //   GET  /config/providers        { providers:[{ id, models:{ <modelID>:{...} } }], default:{...} }

    private fun openCodeSend(
        p: ProviderConfig, key: String?, model: String, system: String, text: String,
        session: String?, onSession: (String) -> Unit,
    ): String {
        val slow = http.newBuilder().readTimeout(30, TimeUnit.MINUTES).build() // agent 可能執行很久
        fun exec(rb: Request.Builder): String = slow.newCall(rb.auth(p, key).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}：${body.take(500)}")
            body
        }
        val sid = session ?: json.parseToJsonElement(exec(post("${base(p)}/session", buildJsonObject {})))
            .jsonObject["id"]?.str()?.also(onSession) ?: throw IOException("無法建立 OpenCode session")

        val body = buildJsonObject {
            putJsonArray("parts") { addJsonObject { put("type", "text"); put("text", text) } }
            if ('/' in model) putJsonObject("model") {
                put("providerID", model.substringBefore('/'))
                put("modelID", model.substringAfter('/'))
            }
            if (system.isNotBlank()) put("system", system)
        }
        val res = json.parseToJsonElement(exec(post("${base(p)}/session/$sid/message", body))).jsonObject
        res["info"]?.jsonObject?.get("error")?.let { throw IOException("OpenCode 錯誤：$it") }
        return res["parts"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.str() == "text" }
            .joinToString("\n") { it["text"]?.str().orEmpty() }
    }

    // ---------------- 模型清單 / 測試連線 ----------------

    suspend fun listModels(p: ProviderConfig, key: String?): List<String> = withContext(Dispatchers.IO) {
        val url = when (p.type) {
            ApiType.GEMINI -> "${base(p)}/models?pageSize=1000"
            ApiType.OPENCODE -> "${base(p)}/config/providers"
            else -> "${base(p)}/models"
        }
        val req = Request.Builder().url(url).get().auth(p, key).common(p).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}：${body.take(300)}")
            val root = json.parseToJsonElement(body).jsonObject
            when (p.type) {
                ApiType.GEMINI -> root["models"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .filter { m -> m["supportedGenerationMethods"]?.jsonArray?.any { it.str() == "generateContent" } != false }
                    .mapNotNull { it["name"]?.str()?.removePrefix("models/") }
                // 模型以「providerID/modelID」表示，例如 anthropic/claude-sonnet-5-5
                ApiType.OPENCODE -> root["providers"]?.jsonArray.orEmpty().flatMap { prov ->
                    val pid = prov.jsonObject["id"]?.str() ?: return@flatMap emptyList<String>()
                    when (val m = prov.jsonObject["models"]) {
                        is JsonObject -> m.keys.map { "$pid/$it" }
                        is JsonArray -> m.mapNotNull { e -> e.jsonObject["id"]?.str()?.let { "$pid/$it" } }
                        else -> emptyList<String>()
                    }
                }
                else -> root["data"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.str() }
            }.sorted().let { list ->
                // OpenCode：各供應商的預設模型排最前面（例如 opencode/big-pickle、anthropic/claude-…）
                if (p.type != ApiType.OPENCODE) list else {
                    val defaults = (root["default"] as? JsonObject).orEmpty().map { (k, v) -> "$k/${v.str()}" }.toSet()
                    list.filter { it in defaults } + list.filterNot { it in defaults }
                }
            }
        }
    }

    private fun JsonElement.str(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
}
