package com.aichathub.data

data class Preset(
    val label: String,
    val type: ApiType,
    val baseUrl: String,
    val model: String = "",
    val auth: AuthKind = AuthKind.API_KEY,
    val hint: String = "",
) {
    fun toConfig() = ProviderConfig(name = label, type = type, baseUrl = baseUrl, defaultModel = model, auth = auth)
}

/** 預設範本：模型名稱留空者，存檔後按「抓取模型」即可從服務端取得清單 */
object Presets {
    val all = listOf(
        Preset("OpenRouter", ApiType.OPENAI, "https://openrouter.ai/api/v1", "openrouter/auto",
            AuthKind.OAUTH_OPENROUTER, "用 OpenRouter 帳號登入即可使用數百個模型（GPT、Claude、Gemini、Llama…）"),
        Preset("OpenCode Zen", ApiType.OPENAI, "https://opencode.ai/zen/v1",
            hint = "OpenCode 官方精選模型閘道（含免費模型），opencode.ai/auth 取得 API Key"),
        Preset("OpenCode Zen（Claude 模型）", ApiType.ANTHROPIC, "https://opencode.ai/zen/v1",
            hint = "Zen 中的 Claude 系列走 Anthropic Messages 協定，同一把 Zen API Key"),
        Preset("OpenCode Server", ApiType.OPENCODE, "http://192.168.1.100:4096", auth = AuthKind.API_KEY,
            hint = "電腦或雲端主機執行：OPENCODE_SERVER_PASSWORD=密碼 opencode serve --hostname 0.0.0.0 --port 4096；" +
                "API Key 欄填該密碼。可使用你在 OpenCode 中 /connect 登入的所有供應商（含 Claude Pro、Copilot 等帳號登入）"),
        Preset("OpenAI", ApiType.OPENAI, "https://api.openai.com/v1", hint = "platform.openai.com 取得 API Key"),
        Preset("Anthropic Claude", ApiType.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-5-5",
            hint = "console.anthropic.com 取得 API Key"),
        Preset("Google Gemini", ApiType.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
            hint = "aistudio.google.com 取得 API Key"),
        Preset("Groq", ApiType.OPENAI, "https://api.groq.com/openai/v1"),
        Preset("DeepSeek", ApiType.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat"),
        Preset("Mistral", ApiType.OPENAI, "https://api.mistral.ai/v1", "mistral-large-latest"),
        Preset("xAI Grok", ApiType.OPENAI, "https://api.x.ai/v1"),
        Preset("Together AI", ApiType.OPENAI, "https://api.together.xyz/v1"),
        Preset("Ollama（本地）", ApiType.OPENAI, "http://192.168.1.100:11434/v1", "llama3.2", AuthKind.NONE,
            "電腦上執行：OLLAMA_HOST=0.0.0.0 ollama serve，並把 IP 改成電腦的區網 IP"),
        Preset("LM Studio（本地）", ApiType.OPENAI, "http://192.168.1.100:1234/v1", auth = AuthKind.NONE,
            hint = "LM Studio → Developer → Start Server，並開啟「Serve on Local Network」"),
        Preset("自訂（OpenAI 相容）", ApiType.OPENAI, "https://", hint = "vLLM、LiteLLM、Azure 代理、公司內部閘道等"),
    )
}
