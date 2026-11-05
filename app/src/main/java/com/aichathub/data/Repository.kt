package com.aichathub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private fun loadProviders(): List<ProviderConfig> =
        prefs.getString("providers", null)
            ?.let { runCatching { json.decodeFromString<List<ProviderConfig>>(it) }.getOrNull() }
            ?: emptyList()

    private fun loadConversations(): List<Conversation> =
        convDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<Conversation>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    private fun persistProviders(list: List<ProviderConfig>) {
        _providers.value = list
        prefs.edit().putString("providers", json.encodeToString(list)).apply()
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
