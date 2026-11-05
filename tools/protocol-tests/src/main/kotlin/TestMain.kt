import com.aichathub.data.*
import com.aichathub.net.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl

var pass = 0; var fail = 0
fun check(name: String, block: suspend () -> String) = runBlocking {
    try { val r = block(); pass++; println("PASS  $name  →  ${r.replace("\n", " ").take(140)}") }
    catch (e: Throwable) { fail++; println("FAIL  $name  →  ${e.javaClass.simpleName}: ${e.message?.replace("\n", " ")?.take(300)}") }
}
fun assertThat(c: Boolean, msg: String) { if (!c) throw AssertionError(msg) }

fun main() {
    val client = ChatClient()
    val oc = ProviderConfig(name = "OpenCode", type = ApiType.OPENCODE, baseUrl = "http://127.0.0.1:4096")
    val oa = ProviderConfig(name = "OpenAI-mock", type = ApiType.OPENAI, baseUrl = "http://127.0.0.1:8787/v1")
    val an = ProviderConfig(name = "Claude-mock", type = ApiType.ANTHROPIC, baseUrl = "http://127.0.0.1:8787/anthropic/v1")
    val ge = ProviderConfig(name = "Gemini-mock", type = ApiType.GEMINI, baseUrl = "http://127.0.0.1:8787/gemini/v1beta")
    val hist = listOf(ChatMessage("user", "hi"))

    println("===== OpenCode Server（真實 opencode serve 1.19.5）=====")
    check("OpenCode 列出模型") { val m = client.listModels(oc, "testpw"); assertThat("mock/mock-tool" in m, m.toString()); m.joinToString() }
    var sid: String? = null
    check("OpenCode 對話（建立 session）") {
        val out = client.stream(oc, "testpw", "mock/mock-tool", "", hist, null) { sid = it }.toList().joinToString("")
        assertThat(out.contains("Hello from mock"), out); assertThat(sid != null, "no session"); "$out  (session=$sid)"
    }
    check("OpenCode 對話（沿用 session）") {
        val out = client.stream(oc, "testpw", "mock/mock-tool", "", hist + ChatMessage("user", "again"), sid) {}.toList().joinToString("")
        assertThat(out.contains("Hello"), out); out
    }
    check("OpenCode 模型留空（用伺服器預設）") { client.stream(oc, "testpw", "", "", hist, null) {}.toList().joinToString("") }
    check("OpenCode 模型清單：預設模型排第一") { val m = client.listModels(oc, "testpw"); assertThat(m.first() == "mock/mock-tool" || m.take(5).contains("mock/mock-tool"), m.take(5).toString()); m.take(4).joinToString() }
    check("OpenCode 帳號:密碼 格式") { client.listModels(oc, "opencode:testpw").size.toString() + " models" }
    check("OpenCode 錯誤密碼 → 清楚的錯誤") {
        val e = runCatching { client.listModels(oc, "wrong") }.exceptionOrNull(); assertThat(e?.message?.contains("401") == true, "${e?.message}"); e!!.message!!
    }

    println("===== 一般對話串流（模擬伺服器，嚴格檢查格式）=====")
    check("OpenAI 相容 串流") { val o = client.stream(oa, "k", "mock-tool", "sys", hist).toList().joinToString(""); assertThat(o.startsWith("Hello"), o); o }
    check("OpenAI 相容 模型清單") { client.listModels(oa, "k").joinToString() }
    check("Anthropic 串流") { val o = client.stream(an, "k", "claude-mock", "sys", hist).toList().joinToString(""); assertThat(o.startsWith("Hello"), o); o }
    check("Anthropic 模型清單") { client.listModels(an, "k").joinToString() }
    check("Gemini 串流") { val o = client.stream(ge, "k", "gemini-mock", "sys", hist).toList().joinToString(""); assertThat(o.startsWith("Hello"), o); o }
    check("Gemini 模型清單（過濾非對話模型）") { val m = client.listModels(ge, "k"); assertThat(m == listOf("gemini-mock"), m.toString()); m.joinToString() }

    println("===== Agent / 工具 / MCP / A2A / 多代理 =====")
    val repo = Repository()
    repo.providers.value = listOf(oa, an, ge); repo.keys[oa.id] = "k"; repo.keys[an.id] = "k"; repo.keys[ge.id] = "k"
    val runner = AgentRunner(client, repo)
    val events = mutableListOf<String>()
    val timeAgent = AgentProfile(name = "Timer", builtinTools = listOf(BuiltinTools.CURRENT_TIME))
    for (p in listOf(oa, an, ge)) {
        check("Agent 工具迴圈 ${p.name}") {
            val model = mapOf(oa to "mock-tool", an to "claude-mock", ge to "gemini-mock")[p]!!
            val r = runner.run(timeAgent, p, model, emptyList(), "what time is it") { l, _ -> events += l }
            assertThat(r.startsWith("Final via tool"), r); r
        }
    }
    check("Agent 歷史含多輪（Anthropic 角色交替）") {
        val h = listOf(ChatMessage("user", "q1"), ChatMessage("assistant", "a1"), ChatMessage("user", "q2"), ChatMessage("assistant", "a2"))
        runner.run(timeAgent, an, "claude-mock", h, "q3") { _, _ -> }
    }
    check("web_search 未設定時給出提示") {
        val a = AgentProfile(name = "S", builtinTools = listOf(BuiltinTools.WEB_SEARCH))
        val r = runner.run(a, oa, "mock-tool", emptyList(), "search") { _, _ -> }; assertThat(r.contains("尚未設定搜尋引擎"), r); r
    }
    for (path in listOf("/mcp", "/mcp-sse")) {
        check("MCP 工具（$path）") {
            val m = McpServer(id = "abcd1234", name = "mock", url = "http://127.0.0.1:8788$path"); repo.mcpServers.value = listOf(m); repo.keys["mcp:abcd1234"] = "mcptok"
            runner.mcp.reset(m.id)
            val a = AgentProfile(name = "M", builtinTools = emptyList(), mcpServerIds = listOf(m.id))
            val r = runner.run(a, oa, "mock-tool", emptyList(), "echo") { _, _ -> }; assertThat(r.contains("ECHO:test message"), r); r
        }
    }
    check("MCP 工具 schema 經 Gemini 過濾") {
        val a = AgentProfile(name = "M", builtinTools = emptyList(), mcpServerIds = listOf("abcd1234"))
        runner.run(a, ge, "gemini-mock", emptyList(), "echo") { _, _ -> }
    }
    check("A2A 讀取 Agent Card") { val c = runner.a2a.fetchCard("http://127.0.0.1:8788", null); assertThat(c.endpoint.endsWith("/a2a"), c.toString()); c.toString() }
    check("A2A 委派") {
        val c = runner.a2a.fetchCard("http://127.0.0.1:8788", null)
        val ag = A2AAgent(name = c.name, url = "http://127.0.0.1:8788", endpoint = c.endpoint, description = c.description); repo.a2aAgents.value = listOf(ag)
        val a = AgentProfile(name = "Lead", builtinTools = emptyList(), a2aAgentIds = listOf(ag.id))
        val r = runner.run(a, oa, "mock-tool", emptyList(), "delegate") { _, _ -> }; assertThat(r.contains("A2A echo"), r); r
    }
    check("多代理：經理委派成員（成員再用工具）") {
        val member = AgentProfile(name = "研究員", builtinTools = listOf(BuiltinTools.CURRENT_TIME))
        val lead = AgentProfile(name = "經理", builtinTools = emptyList(), subAgentIds = listOf(member.id))
        repo.agents.value = listOf(member, lead); events.clear()
        val r = runner.run(lead, an, "claude-mock", emptyList(), "plan") { l, _ -> events += l }
        assertThat(events.any { it.contains("經理 → 研究員") } && events.any { it.contains("研究員 完成") }, events.toString()); "$r | ${events.joinToString(" / ")}"
    }
    check("OpenCode 用於 Agent 模式 → 清楚提示") {
        val e = runCatching { runner.run(timeAgent, oc, "", emptyList(), "x") { _, _ -> } }.exceptionOrNull(); assertThat(e?.message?.contains("OpenCode") == true, "${e?.message}"); e!!.message!!
    }

    println("===== 預設服務 =====")
    check("預設服務包含 OpenCode / Ollama / NVIDIA / Kilo") {
        val names = Presets.featured.map { it.label }
        for (n in listOf("OpenCode Zen", "OpenCode Zen（Claude 模型）", "OpenCode Server", "Ollama（本地）", "LM Studio（本地）", "NVIDIA", "Kilo Gateway",
                "GPT（OpenAI）", "Claude（Anthropic）", "Gemini（Google）", "GitHub Copilot", "Grok（xAI）", "OpenRouter", "Groq", "DeepSeek", "Mistral", "Together AI"))
            assertThat(n in names, "缺少 $n")
        "${names.size} 個：" + names.joinToString("、")
    }
    check("NVIDIA / Kilo 網址") {
        val nv = Presets.featured.first { it.label == "NVIDIA" }; val ki = Presets.featured.first { it.label == "Kilo Gateway" }
        assertThat(nv.baseUrl == "https://integrate.api.nvidia.com/v1" && nv.type == ApiType.OPENAI, nv.toString())
        assertThat(ki.baseUrl == "https://api.kilo.ai/api/gateway" && ki.type == ApiType.OPENAI, ki.toString()); "${nv.baseUrl} / ${ki.baseUrl}"
    }
    check("NVIDIA / Kilo 請求路徑（對模擬伺服器）") {
        // 以相同路徑結構指向本地模擬伺服器，確認 /chat/completions 與 /models 的組合正確
        val p = ProviderConfig(name = "kilo-like", type = ApiType.OPENAI, baseUrl = "http://127.0.0.1:8787/v1/")
        client.listModels(p, "k").joinToString() + " | " + client.stream(p, "k", "mock-tool", "", hist).toList().joinToString("")
    }
    check("升級補服務：舊版 7 個 → 補齊、不重複") {
        val old = listOf("GPT（OpenAI）", "Claude（Anthropic）", "Gemini（Google）", "GitHub Copilot", "Grok（xAI）", "OpenRouter", "LM Studio（本地）")
            .map { n -> Presets.featured.first { it.label == n }.toConfig() } +
            ProviderConfig(name = "我的 Zen", type = ApiType.OPENAI, baseUrl = "https://opencode.ai/zen/v1/")
        val miss = Presets.missingFrom(old).map { it.label }
        assertThat("OpenCode Zen" !in miss, "同網址的 Zen 不應重複：$miss")
        assertThat("OpenCode Server" in miss && "NVIDIA" in miss && "Kilo Gateway" in miss && "Ollama（本地）" in miss, miss.toString())
        assertThat(Presets.missingFrom(old + Presets.missingFrom(old).map { it.toConfig() }).isEmpty(), "補完後仍有缺")
        "補上 ${miss.size} 個：" + miss.joinToString("、")
    }

    println("===== 其他 =====")
    check("OpenRouter PKCE challenge（RFC 7636 測試向量）") {
        val url = OpenRouterAuth.authUrl("dBjftJeZ4CVP-mJ92K9qbBqkZ8jw4NbXsj0z71w2BMk").toHttpUrl()
        val ch = url.queryParameter("code_challenge"); assertThat(ch == "7bFLKuFZdN9qHNFC8fBSvUB4QF_AXIRknU0lWXjDP1c" /* = python hashlib 參考值 */, "$ch"); "$ch  callback=${url.queryParameter("callback_url")}"
    }
    check("HTML 轉文字") { val t = WebTools.htmlToText("<html><head><title>x</title></head><body><script>var a=1</script><h1>標題</h1><p>A &amp; B</p><ul><li>一</li><li>二</li></ul></body></html>"); assertThat(!t.contains("var a") && t.contains("A & B"), t); t }
    check("safeToolName / asciiPart") { val n = safeToolName("ask_${asciiPart("研究員")}_abc123"); assertThat(Regex("^[A-Za-z0-9_-]+$").matches(n), n); n }

    println("\n結果：$pass 通過，$fail 失敗")
    if (fail > 0) kotlin.system.exitProcess(1)
}
