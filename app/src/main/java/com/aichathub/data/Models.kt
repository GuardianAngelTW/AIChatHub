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
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)
