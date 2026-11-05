package com.aichathub.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aichathub.data.ApiType
import com.aichathub.data.AuthKind
import com.aichathub.data.Presets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(vm: ChatViewModel, onBack: () -> Unit, onEdit: (com.aichathub.data.ProviderConfig) -> Unit) {
    val providers by vm.providers.collectAsState()
    var choosePreset by remember { mutableStateOf(providers.isEmpty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 服務設定") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        floatingActionButton = { FloatingActionButton({ choosePreset = true }) { Icon(Icons.Default.Add, "新增") } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(providers, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { onEdit(p) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("${p.type.label} · ${p.auth.label}", style = MaterialTheme.typography.bodySmall)
                        Text(p.baseUrl, style = MaterialTheme.typography.bodySmall)
                        val status = when {
                            p.auth == AuthKind.NONE -> "本地服務"
                            vm.repo.hasKey(p.id) -> "✅ 已設定金鑰 / 已登入"
                            else -> "⚠️ 尚未設定金鑰"
                        }
                        Text("$status · ${p.models.size} 個模型", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (choosePreset) {
        AlertDialog(
            onDismissRequest = { choosePreset = false },
            title = { Text("選擇要新增的服務") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(Presets.all) { preset ->
                        Column(Modifier.fillMaxWidth().clickable { choosePreset = false; onEdit(preset.toConfig()) }.padding(vertical = 10.dp)) {
                            Text(preset.label, style = MaterialTheme.typography.titleSmall)
                            if (preset.hint.isNotBlank()) Text(preset.hint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ choosePreset = false }) { Text("關閉") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProviderScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val original by vm.editing.collectAsState()
    val base = original ?: run { onBack(); return }
    val ctx = LocalContext.current

    var name by remember(base.id) { mutableStateOf(base.name) }
    var type by remember(base.id) { mutableStateOf(base.type) }
    var url by remember(base.id) { mutableStateOf(base.baseUrl) }
    var auth by remember(base.id) { mutableStateOf(base.auth) }
    var model by remember(base.id, base.defaultModel) { mutableStateOf(base.defaultModel) }
    var key by remember(base.id) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val hasKey = vm.repo.hasKey(base.id)

    fun draft() = base.copy(name = name.trim(), type = type, baseUrl = url.trim(), auth = auth, defaultModel = model.trim())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.providers.value.any { it.id == base.id }) "編輯服務" else "新增服務") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("顯示名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("API 協定", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApiType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
            }

            OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("本地模型請填電腦的區網 IP，例如 http://192.168.1.50:11434/v1") })

            Text("驗證方式", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthKind.entries.forEach { a -> FilterChip(selected = auth == a, onClick = { auth = a }, label = { Text(a.label) }) }
            }

            when (auth) {
                AuthKind.API_KEY -> OutlinedTextField(
                    key, { key = it }, label = { Text(if (type == ApiType.OPENCODE) "伺服器密碼（或 帳號:密碼）" else "API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (hasKey) "已加密儲存（留空則不變更）" else "sk-…") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton({ showKey = !showKey }) { Text(if (showKey) "隱藏" else "顯示") } },
                )
                AuthKind.OAUTH_OPENROUTER -> Column {
                    Text(if (hasKey) "✅ 已登入 OpenRouter" else "尚未登入", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        val p = draft()
                        vm.saveProvider(p, null)
                        val authUrl = vm.beginOpenRouterLogin(p.id)
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                    }) { Text(if (hasKey) "重新登入 OpenRouter" else "使用 OpenRouter 帳號登入") }
                }
                AuthKind.NONE -> Text("本地服務（Ollama / LM Studio）通常不需要金鑰。", style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(model, { model = it }, label = { Text("預設模型") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(if (type == ApiType.OPENCODE) "格式 providerID/modelID，留空則用 OpenCode 預設模型" else "可按「測試連線」自動抓取模型清單")
                })
            if (base.models.isNotEmpty()) {
                Text("可用模型（點選設為預設）：", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    base.models.forEach { m ->
                        Text(m, Modifier.fillMaxWidth().clickable { model = m }.padding(vertical = 6.dp),
                            color = if (m == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.fetchModels(draft(), key) { vm.editing.value = it } }) { Text("測試連線") }
                Button(
                    enabled = name.isNotBlank() && url.startsWith("http"),
                    onClick = { vm.saveProvider(draft(), key.ifBlank { null }); onBack() },
                ) { Text("儲存") }
                if (vm.providers.value.any { it.id == base.id }) {
                    TextButton({ vm.deleteProvider(base.id); onBack() }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
