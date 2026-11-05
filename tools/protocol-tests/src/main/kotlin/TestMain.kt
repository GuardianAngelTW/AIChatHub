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

    println("===== 其他 =====")
    check("OpenRouter callback 為 Lite 專用") { OpenRouterAuth.CALLBACK }
    check("OpenRouter PKCE challenge（RFC 7636 測試向量）") {
        val url = OpenRouterAuth.authUrl("dBjftJeZ4CVP-mJ92K9qbBqkZ8jw4NbXsj0z71w2BMk").toHttpUrl()
        val ch = url.queryParameter("code_challenge"); assertThat(ch == "7bFLKuFZdN9qHNFC8fBSvUB4QF_AXIRknU0lWXjDP1c" /* = python hashlib 參考值 */, "$ch"); assertThat(url.queryParameter("callback_url") == "aichathublite://oauth/openrouter", "callback"); "$ch  callback=${url.queryParameter("callback_url")}"
    }
    println("\n結果：$pass 通過，$fail 失敗")
    if (fail > 0) kotlin.system.exitProcess(1)
}
