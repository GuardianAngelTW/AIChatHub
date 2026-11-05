package com.aichathub.net

import com.aichathub.data.ApiType
import com.aichathub.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.UUID

// ======================= Agent 共用型別 =======================

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)
data class ToolCall(val id: String, val name: String, val args: JsonObject)

sealed interface AMsg {
    data class User(val text: String) : AMsg
    /** raw：保留供應商原始內容（Claude thinking、Gemini thoughtSignature 需原樣回傳） */
    data class Assistant(val text: String, val calls: List<ToolCall> = emptyList(), val raw: JsonElement? = null) : AMsg
    data class ToolResult(val call: ToolCall, val content: String) : AMsg
}

data class Completion(val text: String, val calls: List<ToolCall>, val raw: JsonElement?)

fun stringParams(required: List<String>, vararg props: Pair<String, String>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        props.forEach { (k, d) -> putJsonObject(k) { put("type", "string"); put("description", d) } }
    }
    putJsonArray("required") { required.forEach { add(it) } }
}

/** 工具名稱只能用 [A-Za-z0-9_-]，最長 64 */
fun safeToolName(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "tool" }

fun asciiPart(s: String, max: Int = 24) = s.filter { it.isLetterOrDigit() && it.code < 128 }.take(max)

private fun genId() = "call_" + UUID.randomUUID().toString().replace("-", "").take(20)
private fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private val EMPTY = JsonObject(emptyMap())
private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseArgs(e: JsonElement?): JsonObject = when (e) {
    is JsonObject -> e
    is JsonPrimitive -> e.s()?.let { runCatching { lenient.parseToJsonElement(it).jsonObject }.getOrNull() } ?: EMPTY
    else -> EMPTY
}

// ======================= 非串流、帶工具的對話 =======================

suspend fun ChatClient.complete(
    p: ProviderConfig,
    key: String?,
    model: String,
    system: String,
    msgs: List<AMsg>,
    tools: List<ToolSpec>,
): Completion = withContext(Dispatchers.IO) {
    val rb = when (p.type) {
        ApiType.OPENAI -> post("${base(p)}/chat/completions", openAiBody(model, system, msgs, tools))
        ApiType.ANTHROPIC -> post("${base(p)}/messages", anthropicBody(model, system, msgs, tools))
        ApiType.GEMINI -> post("${base(p)}/models/${model.removePrefix("models/")}:generateContent", geminiBody(system, msgs, tools))
        ApiType.OPENCODE -> throw IOException("OpenCode Server 不支援外部工具：請改用一般對話（OpenCode 本身就是 agent，自帶工具）")
    }
    val call = http.newCall(rb.auth(p, key).common(p).build())
    coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
    val body = call.execute().use { r ->
        val b = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}：${b.take(500)}")
        b
    }
    val root = json.parseToJsonElement(body).jsonObject
    when (p.type) {
        ApiType.OPENAI -> parseOpenAi(root)
        ApiType.ANTHROPIC -> parseAnthropic(root)
        ApiType.GEMINI -> parseGemini(root)
        ApiType.OPENCODE -> error("unreachable")
    }
}

// ---------- OpenAI 相容 ----------

private fun openAiBody(model: String, system: String, msgs: List<AMsg>, tools: List<ToolSpec>) = buildJsonObject {
    put("model", model)
    putJsonArray("messages") {
        if (system.isNotBlank()) addJsonObject { put("role", "system"); put("content", system) }
        msgs.forEach { m ->
            when (m) {
                is AMsg.User -> addJsonObject { put("role", "user"); put("content", m.text) }
                is AMsg.Assistant -> addJsonObject {
                    put("role", "assistant")
                    if (m.text.isNotEmpty() || m.calls.isEmpty()) put("content", m.text) else put("content", JsonNull)
                    if (m.calls.isNotEmpty()) putJsonArray("tool_calls") {
                        m.calls.forEach { c ->
                            addJsonObject {
                                put("id", c.id); put("type", "function")
                                putJsonObject("function") { put("name", c.name); put("arguments", c.args.toString()) }
                            }
                        }
                    }
                }
                is AMsg.ToolResult -> addJsonObject {
                    put("role", "tool"); put("tool_call_id", m.call.id); put("content", m.content)
                }
            }
        }
    }
    if (tools.isNotEmpty()) putJsonArray("tools") {
        tools.forEach { t ->
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name); put("description", t.description); put("parameters", t.parameters)
                }
            }
        }
    }
}

private fun parseOpenAi(root: JsonObject): Completion {
    val msg = root["choices"].arr().firstOrNull().obj()?.get("message").obj()
        ?: throw IOException("回應格式錯誤：${root.toString().take(300)}")
    val calls = msg["tool_calls"].arr().mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        val f = o["function"].obj() ?: return@mapNotNull null
        ToolCall(o["id"].s() ?: genId(), f["name"].s() ?: return@mapNotNull null, parseArgs(f["arguments"]))
    }
    return Completion(msg["content"].s().orEmpty(), calls, null)
}

// ---------- Anthropic ----------

private fun anthropicBody(model: String, system: String, msgs: List<AMsg>, tools: List<ToolSpec>) = buildJsonObject {
    put("model", model)
    put("max_tokens", 8192)
    if (system.isNotBlank()) put("system", system)
    put("messages", buildJsonArray {
        val pending = mutableListOf<JsonObject>()
        fun flush(extraText: String? = null) {
            if (pending.isEmpty() && extraText == null) return
            val blocks = pending.toList() + listOfNotNull(extraText?.let { t -> buildJsonObject { put("type", "text"); put("text", t) } })
            pending.clear()
            addJsonObject { put("role", "user"); put("content", JsonArray(blocks)) }
        }
        msgs.forEach { m ->
            when (m) {
                is AMsg.User -> if (pending.isNotEmpty()) flush(m.text) else addJsonObject { put("role", "user"); put("content", m.text) }
                is AMsg.Assistant -> {
                    flush()
                    addJsonObject {
                        put("role", "assistant")
                        put("content", m.raw ?: buildJsonArray {
                            if (m.text.isNotEmpty() || m.calls.isEmpty()) addJsonObject { put("type", "text"); put("text", m.text.ifEmpty { "…" }) }
                            m.calls.forEach { c ->
                                addJsonObject { put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", c.args) }
                            }
                        })
                    }
                }
                is AMsg.ToolResult -> pending += buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", m.call.id); put("content", m.content)
                }
            }
        }
        flush()
    })
    if (tools.isNotEmpty()) putJsonArray("tools") {
        tools.forEach { t ->
            addJsonObject { put("name", t.name); put("description", t.description); put("input_schema", t.parameters) }
        }
    }
}

private fun parseAnthropic(root: JsonObject): Completion {
    val content = root["content"].arr()
    val blocks = content.mapNotNull { it.obj() }
    val text = blocks.filter { it["type"].s() == "text" }.joinToString("") { it["text"].s().orEmpty() }
    val calls = blocks.filter { it["type"].s() == "tool_use" }.map {
        ToolCall(it["id"].s() ?: genId(), it["name"].s().orEmpty(), it["input"].obj() ?: EMPTY)
    }
    return Completion(text, calls, content)
}

// ---------- Gemini ----------

private val geminiKeys = setOf("type", "description", "properties", "items", "required", "enum", "format", "nullable")

/** Gemini 只接受 OpenAPI schema 子集，移除 $schema、additionalProperties 等欄位 */
private fun geminiSchema(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.filterKeys { it in geminiKeys }.mapValues { (k, v) ->
        when {
            k == "properties" -> JsonObject((v as? JsonObject).orEmpty().mapValues { geminiSchema(it.value) })
            k == "items" -> geminiSchema(v)
            k == "type" && v is JsonArray -> JsonPrimitive(v.firstOrNull { it.s() != "null" }.s() ?: "string")
            else -> v
        }
    })
    else -> e
}

private fun geminiBody(system: String, msgs: List<AMsg>, tools: List<ToolSpec>) = buildJsonObject {
    if (system.isNotBlank()) putJsonObject("systemInstruction") {
        putJsonArray("parts") { addJsonObject { put("text", system) } }
    }
    put("contents", buildJsonArray {
        val pending = mutableListOf<JsonObject>()
        fun flush(extraText: String? = null) {
            if (pending.isEmpty() && extraText == null) return
            val parts = pending.toList() + listOfNotNull(extraText?.let { t -> buildJsonObject { put("text", t) } })
            pending.clear()
            addJsonObject { put("role", "user"); put("parts", JsonArray(parts)) }
        }
        msgs.forEach { m ->
            when (m) {
                is AMsg.User -> if (pending.isNotEmpty()) flush(m.text) else addJsonObject {
                    put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", m.text) } }
                }
                is AMsg.Assistant -> {
                    flush()
                    addJsonObject {
                        put("role", "model")
                        put("parts", m.raw ?: buildJsonArray {
                            if (m.text.isNotEmpty() || m.calls.isEmpty()) addJsonObject { put("text", m.text) }
                            m.calls.forEach { c -> addJsonObject { putJsonObject("functionCall") { put("name", c.name); put("args", c.args) } } }
                        })
                    }
                }
                is AMsg.ToolResult -> pending += buildJsonObject {
                    putJsonObject("functionResponse") {
                        if (!m.call.id.startsWith("call_")) put("id", m.call.id)
                        put("name", m.call.name)
                        putJsonObject("response") { put("result", m.content) }
                    }
                }
            }
        }
        flush()
    })
    if (tools.isNotEmpty()) putJsonArray("tools") {
        addJsonObject {
            putJsonArray("functionDeclarations") {
                tools.forEach { t ->
                    addJsonObject {
                        put("name", t.name); put("description", t.description)
                        val props = t.parameters["properties"] as? JsonObject
                        if (props != null && props.isNotEmpty()) put("parameters", geminiSchema(t.parameters))
                    }
                }
            }
        }
    }
}

private fun parseGemini(root: JsonObject): Completion {
    val cand = root["candidates"].arr().firstOrNull().obj()
        ?: throw IOException("Gemini 沒有回應：${root["promptFeedback"] ?: root.toString().take(300)}")
    val parts = cand["content"].obj()?.get("parts").arr()
    val objs = parts.mapNotNull { it.obj() }
    val text = objs.filter { it["thought"].s() != "true" }.mapNotNull { it["text"].s() }.joinToString("")
    val calls = objs.mapNotNull { it["functionCall"].obj() }.map {
        ToolCall(it["id"].s() ?: genId(), it["name"].s().orEmpty(), it["args"].obj() ?: EMPTY)
    }
    return Completion(text, calls, parts)
}
