package com.aichathub.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** 三種 API 協定即可涵蓋絕大多數雲端與本地模型服務 */
@Serializable
enum class ApiType(val label: String) {
    OPENAI("OpenAI 相容"),      // OpenAI、OpenRouter、Groq、DeepSeek、Mistral、xAI、Ollama、LM Studio、vLLM…
    ANTHROPIC("Anthropic"),     // Claude Messages API
    GEMINI("Google Gemini"),    // Gemini generateContent API
    OPENCODE("OpenCode Server"), // `opencode serve` 的 HTTP API（可用 OpenCode 裡登入的所有供應商）
}

@Serializable
enum class AuthKind(val label: String) {
    API_KEY("API Key"),
    OAUTH_OPENROUTER("OpenRouter 帳號登入"),
    OAUTH_GITHUB_COPILOT("GitHub 帳號登入"),
    NONE("不需驗證（本地）"),
}

@Serializable
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ApiType,
    val baseUrl: String,
    val defaultModel: String = "",
    val auth: AuthKind = AuthKind.API_KEY,
    val models: List<String> = emptyList(),
)

@Serializable
data class ChatMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val ts: Long = System.currentTimeMillis(),
    val error: Boolean = false,
    /** role == "tool" 時：顯示的步驟標籤，例如「🔧 研究員 › web_search」 */
    val tool: String? = null,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新對話",
    val providerId: String? = null,
    val model: String = "",
    val systemPrompt: String = "",
    /** OpenCode Server 端的 session id（伺服器自己保存對話歷史） */
    val remoteSession: String? = null,
    /** 設定後此對話以 Agent 模式執行（可呼叫工具、委派團隊成員） */
    val agentId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

// ======================= Agent / 工具 =======================

object BuiltinTools {
    const val WEB_SEARCH = "web_search"
    const val FETCH_URL = "fetch_url"
    const val CURRENT_TIME = "current_time"
    val all = listOf(WEB_SEARCH to "上網搜尋", FETCH_URL to "讀取網頁", CURRENT_TIME to "目前時間")
}

@Serializable
data class AgentProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    /** null = 跟隨對話目前選擇的服務與模型 */
    val providerId: String? = null,
    val model: String = "",
    val systemPrompt: String = "",
    val builtinTools: List<String> = listOf(BuiltinTools.WEB_SEARCH, BuiltinTools.FETCH_URL, BuiltinTools.CURRENT_TIME),
    val mcpServerIds: List<String> = emptyList(),
    val a2aAgentIds: List<String> = emptyList(),
    /** 團隊成員：此 Agent 可以把子任務委派給這些 Agent（多代理協作） */
    val subAgentIds: List<String> = emptyList(),
    val maxSteps: Int = 10,
)

@Serializable
data class McpServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val toolCount: Int = 0,
)

@Serializable
data class A2AAgent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val endpoint: String = "",
    val description: String = "",
    val skills: List<String> = emptyList(),
)

@Serializable
enum class SearchEngine(val label: String) {
    NONE("未設定"),
    TAVILY("Tavily"),
    BRAVE("Brave Search"),
    SEARXNG("SearXNG（自架）"),
}

@Serializable
data class SearchConfig(val engine: SearchEngine = SearchEngine.NONE, val searxUrl: String = "")
