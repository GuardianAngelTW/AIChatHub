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

/** 預設範本：模型名稱留空者，存檔後按「測試連線」即可從服務端取得清單 */
object Presets {
    /** 預設服務版本：增加服務時遞增，舊版升級後會自動補上缺少的服務 */
    const val FEATURED_VERSION = 2

    /** 首次啟動（或升級）自動建立的服務，填入金鑰或登入即可使用 */
    val featured = listOf(
        // ---- OpenCode ----
        Preset("OpenCode Zen", ApiType.OPENAI, "https://opencode.ai/zen/v1",
            hint = "OpenCode 官方精選模型閘道（含免費模型），opencode.ai 取得 API Key"),
        Preset("OpenCode Zen（Claude 模型）", ApiType.ANTHROPIC, "https://opencode.ai/zen/v1",
            hint = "Zen 中的 Claude 系列走 Anthropic 協定，使用同一把 Zen API Key"),
        Preset("OpenCode Server", ApiType.OPENCODE, "http://192.168.1.100:4096",
            hint = "電腦執行：opencode serve --hostname 0.0.0.0 --port 4096；IP 改成電腦的區網 IP；沒設密碼則密碼欄留空"),
        // ---- 主要雲端平台 ----
        Preset("GPT（OpenAI）", ApiType.OPENAI, "https://api.openai.com/v1",
            hint = "platform.openai.com/api-keys 取得 API Key"),
        Preset("Claude（Anthropic）", ApiType.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-5-5",
            hint = "console.anthropic.com 取得 API Key"),
        Preset("Gemini（Google）", ApiType.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
            hint = "aistudio.google.com/apikey 取得 API Key（有免費額度）"),
        Preset("GitHub Copilot", ApiType.OPENAI, "https://api.githubcopilot.com", auth = AuthKind.OAUTH_GITHUB_COPILOT,
            hint = "用 GitHub 帳號登入（需 Copilot 訂閱，含免費版）"),
        Preset("Grok（xAI）", ApiType.OPENAI, "https://api.x.ai/v1",
            hint = "console.x.ai 取得 API Key"),
        // ---- 模型閘道 ----
        Preset("OpenRouter", ApiType.OPENAI, "https://openrouter.ai/api/v1", "openrouter/auto",
            AuthKind.OAUTH_OPENROUTER, "用 OpenRouter 帳號登入即可使用數百個模型"),
        Preset("NVIDIA", ApiType.OPENAI, "https://integrate.api.nvidia.com/v1",
            hint = "build.nvidia.com 取得 API Key（nvapi-…），模型如 meta/llama-3.3-70b-instruct"),
        Preset("Kilo Gateway", ApiType.OPENAI, "https://api.kilo.ai/api/gateway",
            hint = "kilo.ai 取得 API Key，模型格式如 anthropic/claude-sonnet-4.5"),
        // ---- 本地模型 ----
        Preset("Ollama（本地）", ApiType.OPENAI, "http://192.168.1.100:11434/v1", "llama3.2", AuthKind.NONE,
            "電腦執行：OLLAMA_HOST=0.0.0.0 ollama serve，IP 改成電腦的區網 IP"),
        Preset("LM Studio（本地）", ApiType.OPENAI, "http://192.168.1.100:1234/v1", auth = AuthKind.NONE,
            hint = "LM Studio → Developer → Start Server，開啟「Serve on Local Network」"),
        // ---- 其他雲端平台 ----
        Preset("Groq", ApiType.OPENAI, "https://api.groq.com/openai/v1", hint = "console.groq.com 取得 API Key"),
        Preset("DeepSeek", ApiType.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat", hint = "platform.deepseek.com 取得 API Key"),
        Preset("Mistral", ApiType.OPENAI, "https://api.mistral.ai/v1", "mistral-large-latest", hint = "console.mistral.ai 取得 API Key"),
        Preset("Together AI", ApiType.OPENAI, "https://api.together.xyz/v1", hint = "api.together.ai 取得 API Key"),
    )

    /** 只出現在「＋」新增清單的範本 */
    val others = listOf(
        Preset("自訂（OpenAI 相容）", ApiType.OPENAI, "https://", hint = "vLLM、LiteLLM、公司內部閘道等"),
    )

    val all = featured + others

    /** 清單中還沒有的預設服務（同名，或同網址且同協定，視為已存在） */
    fun missingFrom(list: List<ProviderConfig>): List<Preset> = featured.filter { f ->
        list.none { it.name == f.label || (it.baseUrl.trimEnd('/') == f.baseUrl.trimEnd('/') && it.type == f.type) }
    }
}
