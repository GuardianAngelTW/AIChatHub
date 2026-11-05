package com.aichathub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 所有資料都只存在手機本機：服務設定（SharedPreferences）、對話（JSON 檔）、金鑰（Keystore 加密） */
class Repository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val convDir = File(context.filesDir, "conversations").apply { mkdirs() }
    val secure = SecureStore(context)

    private val _providers = MutableStateFlow(loadProviders())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _conversations = MutableStateFlow(loadConversations())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private fun loadProviders(): List<ProviderConfig> {
        val saved = prefs.getString("providers", null)
        if (saved == null) { // 首次啟動：建立全部預設服務
            prefs.edit().putInt("featured_version", Presets.FEATURED_VERSION).apply()
            return Presets.featured.map { it.toConfig() }.also { persistRaw(it) }
        }
        val list = runCatching { json.decodeFromString<List<ProviderConfig>>(saved) }.getOrNull() ?: emptyList()
        // 升級：補上新版本加入的預設服務（已有同名或同網址同協定者不重複建立）
        if (prefs.getInt("featured_version", 1) < Presets.FEATURED_VERSION) {
            val added = list + missingFeatured(list).map { it.toConfig() }
            prefs.edit().putInt("featured_version", Presets.FEATURED_VERSION).apply()
            if (added.size != list.size) persistRaw(added)
            return added
        }
        return list
    }

    private fun missingFeatured(list: List<ProviderConfig>) = Presets.missingFrom(list)

    private fun persistRaw(list: List<ProviderConfig>) =
        prefs.edit().putString("providers", json.encodeToString(list)).apply()

    /** 把缺少的預設服務補回來（依名稱判斷） */
    fun restoreFeatured(): Int {
        val missing = missingFeatured(_providers.value)
        if (missing.isNotEmpty()) persistProviders(_providers.value + missing.map { it.toConfig() })
        return missing.size
    }

    private fun loadConversations(): List<Conversation> =
        convDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<Conversation>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    private fun persistProviders(list: List<ProviderConfig>) {
        _providers.value = list
        persistRaw(list)
    }

    /** secret == null 表示不變更已存的金鑰；空字串表示清除 */
    fun saveProvider(p: ProviderConfig, secret: String? = null) {
        secret?.let { if (it.isBlank()) secure.remove(p.id) else secure.put(p.id, it) }
        val list = _providers.value
        persistProviders(if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p)
    }

    fun deleteProvider(id: String) {
        secure.remove(id)
        persistProviders(_providers.value.filterNot { it.id == id })
    }

    fun apiKey(providerId: String): String? = secure.get(providerId)
    fun hasKey(providerId: String) = secure.has(providerId)

    fun saveConversation(c: Conversation) {
        if (c.messages.isEmpty()) return
        File(convDir, "${c.id}.json").writeText(json.encodeToString(c))
        _conversations.update { list -> (listOf(c) + list.filterNot { it.id == c.id }).sortedByDescending { it.updatedAt } }
    }

    fun deleteConversation(id: String) {
        File(convDir, "$id.json").delete()
        _conversations.update { list -> list.filterNot { it.id == id } }
    }

    // ---------------- Agents / MCP / A2A / 搜尋 ----------------

    private inline fun <reified T> load(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> store(key: String, v: T) =
        prefs.edit().putString(key, json.encodeToString(v)).apply()

    private val _agents = MutableStateFlow(load<List<AgentProfile>>("agents") ?: defaultAgents().also { store("agents", it) })
    val agents: StateFlow<List<AgentProfile>> = _agents.asStateFlow()

    private val _mcp = MutableStateFlow(load<List<McpServer>>("mcp") ?: emptyList())
    val mcpServers: StateFlow<List<McpServer>> = _mcp.asStateFlow()

    private val _a2a = MutableStateFlow(load<List<A2AAgent>>("a2a") ?: emptyList())
    val a2aAgents: StateFlow<List<A2AAgent>> = _a2a.asStateFlow()

    private val _search = MutableStateFlow(load<SearchConfig>("search") ?: SearchConfig())
    val search: StateFlow<SearchConfig> = _search.asStateFlow()

    private fun <T> upsert(list: List<T>, item: T, id: (T) -> String) =
        if (list.any { id(it) == id(item) }) list.map { if (id(it) == id(item)) item else it } else list + item

    fun saveAgent(a: AgentProfile) { _agents.value = upsert(_agents.value, a) { it.id }; store("agents", _agents.value) }
    fun deleteAgent(id: String) {
        _agents.value = _agents.value.filterNot { it.id == id }.map { it.copy(subAgentIds = it.subAgentIds - id) }
        store("agents", _agents.value)
    }

    fun saveMcp(m: McpServer, token: String?) {
        token?.let { if (it.isBlank()) secure.remove("mcp:${m.id}") else secure.put("mcp:${m.id}", it) }
        _mcp.value = upsert(_mcp.value, m) { it.id }; store("mcp", _mcp.value)
    }
    fun deleteMcp(id: String) {
        secure.remove("mcp:$id"); _mcp.value = _mcp.value.filterNot { it.id == id }; store("mcp", _mcp.value)
    }
    fun mcpToken(id: String) = secure.get("mcp:$id")

    fun saveA2A(a: A2AAgent, token: String?) {
        token?.let { if (it.isBlank()) secure.remove("a2a:${a.id}") else secure.put("a2a:${a.id}", it) }
        _a2a.value = upsert(_a2a.value, a) { it.id }; store("a2a", _a2a.value)
    }
    fun deleteA2A(id: String) {
        secure.remove("a2a:$id"); _a2a.value = _a2a.value.filterNot { it.id == id }; store("a2a", _a2a.value)
    }
    fun a2aToken(id: String) = secure.get("a2a:$id")

    fun saveSearch(cfg: SearchConfig, key: String?) {
        key?.let { if (it.isNotBlank()) secure.put("search:${cfg.engine.name}", it) }
        _search.value = cfg; store("search", cfg)
    }
    fun searchKey(engine: SearchEngine) = secure.get("search:${engine.name}")
    fun hasSearchKey(engine: SearchEngine) = secure.has("search:${engine.name}")

    private fun defaultAgents(): List<AgentProfile> {
        val web = listOf(BuiltinTools.WEB_SEARCH, BuiltinTools.FETCH_URL, BuiltinTools.CURRENT_TIME)
        val researcher = AgentProfile(
            name = "研究員", description = "負責上網搜尋、閱讀原文並整理重點與來源",
            systemPrompt = "你是研究員。用 web_search 搜尋多個來源，必要時用 fetch_url 閱讀原文，整理成條列重點並附上來源網址。只陳述有來源支持的事實。",
            builtinTools = web,
        )
        val writer = AgentProfile(
            name = "撰稿人", description = "負責把資料寫成結構清楚、易讀的文章或報告",
            systemPrompt = "你是專業撰稿人。根據提供的資料撰寫結構清楚的內容（標題、小節、條列），保留來源標註，使用繁體中文。",
            builtinTools = emptyList(),
        )
        val reviewer = AgentProfile(
            name = "審稿人", description = "負責檢查事實、邏輯與遺漏，提出具體修改",
            systemPrompt = "你是嚴謹的審稿人。檢查內容的事實正確性、邏輯、遺漏與用詞，必要時用 web_search 查證，輸出修訂後的完整版本與修改說明。",
            builtinTools = web,
        )
        val lead = AgentProfile(
            name = "專案經理（多代理團隊）", description = "拆解需求並指揮研究員、撰稿人、審稿人協作",
            systemPrompt = "你是專案經理，帶領一個 AI 團隊。先拆解使用者的需求並規劃步驟，再用 ask_* 工具把子任務分派給合適的成員" +
                "（通常：研究員蒐集資料 → 撰稿人撰寫 → 審稿人審查）。每次分派都要提供完整、可獨立執行的任務說明與必要資料。" +
                "最後整合成果交付給使用者。使用繁體中文。",
            builtinTools = listOf(BuiltinTools.CURRENT_TIME),
            subAgentIds = listOf(researcher.id, writer.id, reviewer.id),
            maxSteps = 12,
        )
        val assistant = AgentProfile(
            name = "上網助理", description = "會自動上網搜尋最新資訊的單一助理",
            systemPrompt = "你是會上網的助理。遇到需要最新或具體事實的問題，先用 web_search 搜尋，必要時用 fetch_url 閱讀原文，回答末尾列出參考來源網址。使用繁體中文。",
            builtinTools = web,
        )
        return listOf(assistant, lead, researcher, writer, reviewer)
    }

    var lastProviderId: String?
        get() = prefs.getString("last_provider", null)
        set(v) = prefs.edit().putString("last_provider", v).apply()

    var lastModel: String?
        get() = prefs.getString("last_model", null)
        set(v) = prefs.edit().putString("last_model", v).apply()

    // OAuth PKCE 暫存（瀏覽器登入期間 App 可能被系統回收）
    var pendingOAuth: String?
        get() = prefs.getString("pending_oauth", null)
        set(v) = prefs.edit().putString("pending_oauth", v).apply()
}
