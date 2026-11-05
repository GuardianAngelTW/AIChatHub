package com.aichathub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichathub.data.ChatMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, openProviders: () -> Unit) {
    val conv by vm.current.collectAsState()
    val convs by vm.conversations.collectAsState()
    val providers by vm.providers.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(false) }
    val provider = providers.find { it.id == conv.providerId }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("AI Chat Hub", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                NavigationDrawerItem(
                    label = { Text("新對話") }, selected = false,
                    icon = { Icon(Icons.Default.Add, null) },
                    onClick = { vm.newChat(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("AI 服務設定") }, selected = false,
                    icon = { Icon(Icons.Default.Settings, null) },
                    onClick = { scope.launch { drawer.close() }; openProviders() },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn {
                    items(convs, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = c.id == conv.id,
                            onClick = { vm.openChat(c.id); scope.launch { drawer.close() } },
                            badge = {
                                IconButton(onClick = { vm.deleteChat(c.id) }) { Icon(Icons.Default.Delete, "刪除") }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "選單") }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(enabled = providers.isNotEmpty()) { showPicker = true },
                        ) {
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(
                                    provider?.name ?: "尚未選擇服務",
                                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    conv.model.ifBlank { provider?.defaultModel.orEmpty() }.ifBlank { "點此選擇模型" },
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSystem = true }) { Icon(Icons.Default.Edit, "系統提示詞") }
                        IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "新對話") }
                    },
                )
            },
            bottomBar = {
                InputBar(
                    streaming = streaming,
                    onSend = vm::send,
                    onStop = vm::stop,
                    modifier = Modifier.navigationBarsPadding().imePadding(),
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    providers.isEmpty() -> EmptyState("還沒有設定任何 AI 服務", "新增 AI 服務", openProviders)
                    conv.messages.isEmpty() -> EmptyState(
                        "開始和 ${provider?.name ?: "AI"} 對話吧",
                        if (provider == null) "選擇服務與模型" else null,
                    ) { showPicker = true }
                    else -> MessageList(conv.messages, streaming, onRegenerate = vm::regenerate)
                }
            }
        }
    }

    if (showPicker) ModelPickerDialog(vm, conv.providerId, conv.model) { showPicker = false }
    if (showSystem) {
        var text by remember { mutableStateOf(conv.systemPrompt) }
        AlertDialog(
            onDismissRequest = { showSystem = false },
            title = { Text("系統提示詞（System Prompt）") },
            text = {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("例如：你是一位使用繁體中文回答的專業助理") })
            },
            confirmButton = { TextButton({ vm.setSystemPrompt(text); showSystem = false }) { Text("儲存") } },
            dismissButton = { TextButton({ showSystem = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyState(text: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (action != null) {
            Spacer(Modifier.size(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, streaming: Boolean, onRegenerate: () -> Unit) {
    val state = rememberLazyListState()
    val lastLen = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(messages.size, lastLen) { if (messages.isNotEmpty()) state.scrollToItem(messages.lastIndex, Int.MAX_VALUE / 2) }
    LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(messages.size) { i ->
            val m = messages[i]
            val isLast = i == messages.lastIndex
            Bubble(m, waiting = streaming && isLast && m.content.isEmpty())
            if (isLast && !streaming && m.role == "assistant") {
                TextButton(onClick = onRegenerate) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Text(" 重新產生")
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, waiting: Boolean) {
    val user = m.role == "user"
    val bg = when {
        m.error -> MaterialTheme.colorScheme.errorContainer
        user -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 340.dp)) {
            if (waiting) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
            } else {
                SelectionContainer { Text(m.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
            }
        }
    }
}

@Composable
private fun InputBar(streaming: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, modifier: Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.weight(1f).heightIn(max = 180.dp),
            placeholder = { Text("輸入訊息…") },
            shape = RoundedCornerShape(24.dp),
        )
        if (streaming) {
            IconButton(onClick = onStop) { Icon(Icons.Default.Close, "停止") }
        } else {
            IconButton(enabled = text.isNotBlank(), onClick = { onSend(text); text = "" }) {
                Icon(Icons.AutoMirrored.Filled.Send, "送出")
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(vm: ChatViewModel, currentProvider: String?, currentModel: String, onDismiss: () -> Unit) {
    val providers by vm.providers.collectAsState()
    var pid by remember { mutableStateOf(currentProvider ?: providers.firstOrNull()?.id) }
    val p = providers.find { it.id == pid }
    var model by remember(pid) {
        mutableStateOf(if (pid == currentProvider && currentModel.isNotBlank()) currentModel else p?.defaultModel.orEmpty())
    }
    var filter by remember(pid) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選擇服務與模型") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("AI 服務與模型", style = MaterialTheme.typography.labelLarge)
                providers.forEach { prov ->
                    Row(Modifier.fillMaxWidth().clickable { pid = prov.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = prov.id == pid, onClick = { pid = prov.id })
                        Text(prov.name)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(model, { model = it }, label = { Text("模型名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (p != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ vm.fetchModels(p) }) { Text("抓取模型清單") }
                        if (p.models.size > 8) OutlinedTextField(filter, { filter = it }, placeholder = { Text("篩選") },
                            singleLine = true, modifier = Modifier.weight(1f))
                    }
                    val shown = p.models.filter { filter.isBlank() || it.contains(filter, ignoreCase = true) }
                    Column {
                        shown.take(200).forEach { m ->
                            Text(
                                m, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (m == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().clickable { model = m }.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pid != null, onClick = {
                vm.selectModel(pid!!, model.trim())
                onDismiss()
            }) { Text("確定") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
