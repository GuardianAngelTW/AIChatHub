package com.aichathub.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.data.ApiType
import com.aichathub.data.AuthKind
import com.aichathub.data.ChatMessage
import com.aichathub.data.Conversation
import com.aichathub.data.ProviderConfig
import com.aichathub.data.Repository
import com.aichathub.net.ChatClient
import com.aichathub.net.CopilotAuth
import com.aichathub.net.OpenRouterAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    private val client = ChatClient()

    val providers = repo.providers
    val conversations = repo.conversations

    private val _current = MutableStateFlow(newConversation())
    val current = _current.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming = _streaming.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast = _toast.asSharedFlow()

    /** 目前正在編輯的服務設定（新增或修改） */
    val editing = MutableStateFlow<ProviderConfig?>(null)

    private var job: Job? = null

    /** GitHub Copilot 裝置碼登入進行中時顯示的驗證碼 */
    val copilotLogin = MutableStateFlow<CopilotAuth.DeviceCode?>(null)
    private var copilotJob: Job? = null

    private fun say(msg: String) { _toast.tryEmit(msg) }

    private fun newConversation(): Conversation {
        val pid = repo.lastProviderId?.takeIf { id -> repo.providers.value.any { it.id == id } }
            ?: repo.providers.value.firstOrNull()?.id
        val p = repo.providers.value.find { it.id == pid }
        return Conversation(providerId = pid, model = repo.lastModel?.takeIf { pid == repo.lastProviderId } ?: p?.defaultModel.orEmpty())
    }

    // ---------------- 對話 ----------------

    fun newChat() { stop(); _current.value = newConversation() }

    fun openChat(id: String) {
        stop()
        conversations.value.find { it.id == id }?.let { _current.value = it }
    }

    fun deleteChat(id: String) {
        repo.deleteConversation(id)
        if (_current.value.id == id) newChat()
    }

    fun selectModel(providerId: String, model: String) {
        repo.lastProviderId = providerId
        repo.lastModel = model
        _current.update {
            it.copy(providerId = providerId, model = model, remoteSession = if (it.providerId == providerId) it.remoteSession else null)
        }
        repo.saveConversation(_current.value)
    }

    fun setSystemPrompt(text: String) {
        _current.update { it.copy(systemPrompt = text) }
        repo.saveConversation(_current.value)
    }

    fun send(text: String) {
        if (text.isBlank() || _streaming.value) return
        val conv = _current.value
        val p = providers.value.find { it.id == conv.providerId } ?: return say("請先在上方選擇 AI 服務")
        val model = conv.model.ifBlank { p.defaultModel }.ifBlank { p.models.firstOrNull().orEmpty() }
        if (model.isBlank() && p.type != ApiType.OPENCODE) return say("請先選擇模型")
        if (p.auth != AuthKind.NONE && p.type != ApiType.OPENCODE && !repo.hasKey(p.id)) return say("「${p.name}」尚未設定 API Key 或登入")

        val withUser = conv.copy(
            messages = conv.messages + ChatMessage("user", text.trim()),
            title = if (conv.messages.isEmpty()) text.trim().take(30) else conv.title,
            model = model,
            updatedAt = System.currentTimeMillis(),
        )
        val history = withUser.messages.filter { !it.error && it.content.isNotBlank() }
        _current.value = withUser.copy(messages = withUser.messages + ChatMessage("assistant", ""))

        job = viewModelScope.launch {
            _streaming.value = true
            val sb = StringBuilder()
            try {
                val remote = conv.remoteSession.takeIf { p.type == ApiType.OPENCODE && conv.providerId == p.id }
                client.stream(p, repo.apiKey(p.id), model, conv.systemPrompt, history, remote) { sid ->
                    _current.update { it.copy(remoteSession = sid) }
                }.collect { delta ->
                    sb.append(delta)
                    updateLast(sb.toString(), false)
                }
                if (sb.isEmpty()) updateLast("（模型沒有回傳內容）", true)
            } catch (e: CancellationException) {
                updateLast(if (sb.isEmpty()) "（已停止）" else sb.toString(), sb.isEmpty())
                throw e
            } catch (e: Exception) {
                updateLast(sb.toString() + (if (sb.isEmpty()) "" else "\n\n") + "⚠️ ${friendly(e, p)}", true)
            } finally {
                _streaming.value = false
                withContext(NonCancellable + Dispatchers.IO) { repo.saveConversation(_current.value) }
            }
        }
    }

    /** 把常見的網路例外轉成看得懂的說明 */
    private fun friendly(e: Exception, p: ProviderConfig?): String {
        val local = p?.baseUrl?.let { it.contains("192.168.") || it.contains("10.") || it.startsWith("http://") } == true
        return when (e) {
            is java.net.ConnectException -> "無法連線到 ${p?.baseUrl}。" + if (local)
                "請確認：手機與電腦在同一個 Wi-Fi、IP 正確、伺服器有綁定 0.0.0.0（OpenCode 需加 --hostname 0.0.0.0），以及電腦防火牆允許該連接埠。" else "請檢查網路。"
            is java.net.SocketTimeoutException -> "連線逾時（${p?.baseUrl}）。伺服器沒有回應，或模型仍在載入。"
            is java.net.UnknownHostException -> "找不到主機：${e.message}。請檢查網址或網路。"
            else -> (e.message ?: e.javaClass.simpleName).let {
                if (it.contains("HTTP 401")) "$it\n（驗證失敗：API Key / 密碼錯誤或已過期）" else it
            }
        }
    }

    /** 重新產生最後一則回覆 */
    fun regenerate() {
        if (_streaming.value) return
        val msgs = _current.value.messages
        val lastUser = msgs.indexOfLast { it.role == "user" }
        if (lastUser < 0) return
        val text = msgs[lastUser].content
        _current.update { it.copy(messages = msgs.take(lastUser)) }
        send(text)
    }

    private fun updateLast(content: String, error: Boolean) = _current.update { c ->
        val last = c.messages.lastOrNull() ?: return@update c
        c.copy(messages = c.messages.dropLast(1) + last.copy(content = content, error = error), updatedAt = System.currentTimeMillis())
    }

    fun stop() { job?.cancel(); job = null }

    // ---------------- 服務設定 ----------------

    fun saveProvider(p: ProviderConfig, secret: String?) {
        repo.saveProvider(p, secret)
        if (_current.value.providerId == null) selectModel(p.id, p.defaultModel)
        editing.value = p
        // 新填入金鑰且還沒有模型清單 → 自動抓取
        if (!secret.isNullOrBlank() && p.models.isEmpty()) fetchModels(p)
    }

    fun restoreFeatured() { say("已補回 ${repo.restoreFeatured()} 個預設服務") }

    // ---------------- GitHub Copilot 登入 ----------------

    fun beginCopilotLogin(p: ProviderConfig) {
        repo.saveProvider(p)
        copilotJob?.cancel()
        copilotJob = viewModelScope.launch {
            try {
                val dc = CopilotAuth.requestDeviceCode(client.http)
                copilotLogin.value = dc
                val token = CopilotAuth.pollToken(client.http, dc)
                repo.secure.put(p.id, token)
                copilotLogin.value = null
                // 驗證 Copilot 權限並抓模型
                fetchModels(providers.value.find { it.id == p.id } ?: p) { editing.value = it }
                say("GitHub Copilot 登入成功")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                copilotLogin.value = null
                say(e.message ?: "登入失敗")
            }
        }
    }

    fun cancelCopilotLogin() { copilotJob?.cancel(); copilotLogin.value = null }

    fun deleteProvider(id: String) {
        repo.deleteProvider(id)
        if (_current.value.providerId == id) _current.update { it.copy(providerId = providers.value.firstOrNull()?.id, model = providers.value.firstOrNull()?.defaultModel.orEmpty()) }
    }

    /** 抓取模型清單（同時作為測試連線），成功後寫回服務設定 */
    fun fetchModels(p: ProviderConfig, keyOverride: String? = null, onDone: (ProviderConfig) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val key = keyOverride?.takeIf { it.isNotBlank() } ?: repo.apiKey(p.id)
                val models = client.listModels(p, key)
                // OpenCode 預設模型留空 = 用伺服器設定的預設模型（避免選到未登入的供應商）
                val updated = p.copy(models = models, defaultModel = if (p.type == ApiType.OPENCODE) p.defaultModel
                    else p.defaultModel.ifBlank { models.firstOrNull().orEmpty() })
                if (providers.value.any { it.id == p.id }) repo.saveProvider(updated)
                say("連線成功，取得 ${models.size} 個模型")
                onDone(updated)
            } catch (e: Exception) {
                say("連線失敗：${friendly(e, p)}")
            }
        }
    }

    // ---------------- OpenRouter 登入 ----------------

    fun beginOpenRouterLogin(providerId: String): String {
        val verifier = OpenRouterAuth.newVerifier()
        repo.pendingOAuth = "$providerId|$verifier"
        return OpenRouterAuth.authUrl(verifier)
    }

    fun handleOAuthCallback(uri: Uri) {
        if (uri.scheme != "aichathublite" || uri.host != "oauth") return
        val code = uri.getQueryParameter("code") ?: return say("登入被取消")
        val (pid, verifier) = repo.pendingOAuth?.split("|")?.takeIf { it.size == 2 } ?: return say("登入狀態已失效，請重試")
        repo.pendingOAuth = null
        viewModelScope.launch {
            try {
                val key = OpenRouterAuth.exchange(client.http, code, verifier)
                repo.secure.put(pid, key)
                say("OpenRouter 登入成功")
                providers.value.find { it.id == pid }?.let { p -> fetchModels(p) { editing.value = it } }
            } catch (e: Exception) {
                say(e.message ?: "登入失敗")
            }
        }
    }
}
