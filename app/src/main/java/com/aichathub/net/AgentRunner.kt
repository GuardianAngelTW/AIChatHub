package com.aichathub.net

import com.aichathub.data.AgentProfile
import com.aichathub.data.BuiltinTools
import com.aichathub.data.ChatMessage
import com.aichathub.data.ProviderConfig
import com.aichathub.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Agent 執行引擎（ReAct / tool-calling 迴圈）：
 * 模型 → 決定呼叫工具 → 執行（內建工具 / MCP / A2A / 團隊成員）→ 結果回饋 → … → 最終回答
 */
class AgentRunner(private val client: ChatClient, private val repo: Repository) {
    val web = WebTools(client.http)
    val mcp = McpClient(client.http)
    val a2a = A2AClient(client.http)

    private class Tool(val spec: ToolSpec, val run: suspend (JsonObject) -> String)

    private fun JsonObject.arg(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** onEvent(標籤, 內容)：回報每一步，讓 UI 即時顯示 Agent 在做什麼 */
    suspend fun run(
        agent: AgentProfile,
        fallbackProvider: ProviderConfig?,
        fallbackModel: String,
        history: List<ChatMessage>,
        input: String,
        depth: Int = 0,
        onEvent: (String, String) -> Unit,
    ): String {
        val p = repo.providers.value.find { it.id == agent.providerId } ?: fallbackProvider
            ?: throw IOException("Agent「${agent.name}」沒有可用的 AI 服務，請在上方選擇服務")
        if (p.type == com.aichathub.data.ApiType.OPENCODE)
            throw IOException("OpenCode Server 本身就是 Agent（自帶工具），不能用於 App 的 Agent 模式：請改用「一般對話」，或為此 Agent 指定其他服務")
        val model = agent.model
            .ifBlank { if (p.id == fallbackProvider?.id) fallbackModel else "" }
            .ifBlank { p.defaultModel }
            .ifBlank { p.models.firstOrNull().orEmpty() }
        if (model.isBlank()) throw IOException("Agent「${agent.name}」沒有指定模型")
        val key = repo.apiKey(p.id)

        val tools = linkedMapOf<String, Tool>()
        fun add(spec: ToolSpec, run: suspend (JsonObject) -> String) { tools[spec.name] = Tool(spec, run) }

        // ---- 內建工具 ----
        if (BuiltinTools.WEB_SEARCH in agent.builtinTools) add(
            ToolSpec(BuiltinTools.WEB_SEARCH, "上網搜尋最新資訊，回傳標題、網址與摘要。需要時事、價格、數據、新聞或你不確定的事實時使用。",
                stringParams(listOf("query"), "query" to "搜尋關鍵字（可用任何語言）")),
        ) { a -> io { web.search(repo.search.value, repo.searchKey(repo.search.value.engine), a.arg("query")) } }

        if (BuiltinTools.FETCH_URL in agent.builtinTools) add(
            ToolSpec(BuiltinTools.FETCH_URL, "讀取指定網址的網頁內容（轉為純文字），用於閱讀搜尋結果的原文。",
                stringParams(listOf("url"), "url" to "完整網址，http:// 或 https:// 開頭")),
        ) { a -> io { web.fetch(a.arg("url")) } }

        if (BuiltinTools.CURRENT_TIME in agent.builtinTools) add(
            ToolSpec(BuiltinTools.CURRENT_TIME, "取得裝置目前的日期、時間與時區。",
                buildJsonObject { put("type", "object"); putJsonObject("properties") {} }),
        ) { io { web.now() } }

        // ---- MCP 伺服器工具 ----
        for (sid in agent.mcpServerIds) {
            val server = repo.mcpServers.value.find { it.id == sid } ?: continue
            val token = repo.mcpToken(server.id)
            try {
                io { mcp.listTools(server, token) }.forEach { t ->
                    add(ToolSpec(safeToolName("m${server.id.take(4)}_${t.name}"), "[MCP：${server.name}] ${t.description}".take(1000), t.inputSchema)) { a ->
                        io { mcp.callTool(server, token, t.name, a) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onEvent("⚠️ MCP ${server.name} 無法連線", e.message.orEmpty())
            }
        }

        // ---- A2A 遠端代理 ----
        for (aid in agent.a2aAgentIds) {
            val remote = repo.a2aAgents.value.find { it.id == aid } ?: continue
            val token = repo.a2aToken(remote.id)
            add(ToolSpec(
                safeToolName("a2a_${asciiPart(remote.name)}_${remote.id.take(6)}"),
                "委派給遠端 A2A 代理「${remote.name}」：${remote.description}" +
                    (if (remote.skills.isNotEmpty()) "（技能：${remote.skills.joinToString("、")}）" else ""),
                stringParams(listOf("message"), "message" to "要交給該代理的完整任務說明"),
            )) { a ->
                onEvent("🌐 ${agent.name} → A2A ${remote.name}", a.arg("message"))
                io { a2a.send(remote, token, a.arg("message")) }
            }
        }

        // ---- 團隊成員（多代理） ----
        val members = if (depth < 3) agent.subAgentIds.mapNotNull { id -> repo.agents.value.find { it.id == id } } else emptyList()
        for (m in members) {
            add(ToolSpec(
                safeToolName("ask_${asciiPart(m.name)}_${m.id.take(6)}"),
                "把子任務交給團隊成員「${m.name}」：${m.description}。成員看不到對話歷史，請提供完整任務說明與所需資料。",
                stringParams(listOf("task"), "task" to "完整、可獨立執行的任務說明（含背景與需要的資料）"),
            )) { a ->
                onEvent("👥 ${agent.name} → ${m.name}", a.arg("task"))
                val out = this@AgentRunner.run(m, p, model, emptyList(), a.arg("task"), depth + 1, onEvent)
                onEvent("✅ ${m.name} 完成", out)
                out
            }
        }

        val system = buildString {
            append(agent.systemPrompt.ifBlank { "你是一個有用的 AI 助理。" })
            append("\n\n目前時間：").append(web.now())
            if (members.isNotEmpty()) {
                append("\n\n你的團隊成員：\n")
                members.forEach { append("- ${it.name}：${it.description}\n") }
            }
            if (tools.isNotEmpty()) append("\n需要時主動使用工具；資訊足夠後直接給出最終回答。")
        }

        val msgs = mutableListOf<AMsg>()
        history.filter { !it.error && it.content.isNotBlank() && (it.role == "user" || it.role == "assistant") }
            .forEach { msgs += if (it.role == "user") AMsg.User(it.content) else AMsg.Assistant(it.content) }
        msgs += AMsg.User(input)

        val specs = tools.values.map { it.spec }
        repeat(agent.maxSteps.coerceIn(1, 30)) {
            val c = client.complete(p, key, model, system, msgs, specs)
            if (c.calls.isEmpty()) return c.text
            msgs += AMsg.Assistant(c.text, c.calls, c.raw)
            if (c.text.isNotBlank()) onEvent("💭 ${agent.name}", c.text)
            for (call in c.calls) {
                val tool = tools[call.name]
                if (!call.name.startsWith("ask_") && !call.name.startsWith("a2a_")) onEvent("🔧 ${agent.name} › ${call.name}", call.args.toString())
                val result = try {
                    tool?.run?.invoke(call.args) ?: "錯誤：沒有名為 ${call.name} 的工具"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "工具執行失敗：${e.message}"
                }
                if (!call.name.startsWith("ask_")) onEvent("📄 ${call.name} 結果", result)
                msgs += AMsg.ToolResult(call, result.take(20000))
            }
        }
        msgs += AMsg.User("（已達工具使用次數上限，請根據目前取得的資訊直接給出最終回答。）")
        // 仍附上工具定義（部分供應商要求歷史中有工具呼叫時必須提供），但不再執行
        return client.complete(p, key, model, system, msgs, specs).text.ifBlank { "（已達步驟上限，未能產生最終回答）" }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
