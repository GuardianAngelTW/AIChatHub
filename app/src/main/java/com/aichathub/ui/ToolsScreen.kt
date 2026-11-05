package com.aichathub.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aichathub.data.SearchConfig
import com.aichathub.data.SearchEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val search by vm.search.collectAsState()
    val mcps by vm.mcpServers.collectAsState()
    val a2as by vm.a2aAgents.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具：搜尋 / MCP / A2A") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 上網搜尋 ----------
            Text("🔍 上網搜尋", style = MaterialTheme.typography.titleMedium)
            Text("使用官方搜尋 API 或自架 SearXNG（不爬取搜尋引擎頁面）。Tavily 與 Brave 皆有免費額度。", style = MaterialTheme.typography.bodySmall)
            var engine by remember(search) { mutableStateOf(search.engine) }
            var searx by remember(search) { mutableStateOf(search.searxUrl) }
            var skey by remember { mutableStateOf("") }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchEngine.entries.forEach { e -> FilterChip(selected = engine == e, onClick = { engine = e }, label = { Text(e.label) }) }
            }
            when (engine) {
                SearchEngine.TAVILY, SearchEngine.BRAVE -> OutlinedTextField(
                    skey, { skey = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("${engine.label} API Key") },
                    placeholder = { Text(if (vm.repo.hasSearchKey(engine)) "已加密儲存（留空不變）" else if (engine == SearchEngine.TAVILY) "tvly-…（tavily.com）" else "api-dashboard.search.brave.com") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                SearchEngine.SEARXNG -> OutlinedTextField(
                    searx, { searx = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("SearXNG 網址") }, placeholder = { Text("https://searx.example.com（需啟用 JSON 格式）") },
                )
                SearchEngine.NONE -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.saveSearch(SearchConfig(engine, searx.trim()), skey.ifBlank { null }); skey = "" }) { Text("儲存") }
                OutlinedButton({ vm.testSearch() }) { Text("測試搜尋") }
            }

            HorizontalDivider()

            // ---------- MCP ----------
            Text("🧩 MCP 伺服器（Model Context Protocol）", style = MaterialTheme.typography.titleMedium)
            Text("連線遠端 MCP 伺服器（Streamable HTTP），把它的工具交給 Agent 使用，例如 GitHub、Notion、資料庫、自建工具。", style = MaterialTheme.typography.bodySmall)
            mcps.forEach { m ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Text("${m.url} · ${m.toolCount} 個工具", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton({ vm.deleteMcp(m.id) }) { Icon(Icons.Default.Delete, "刪除") }
                    }
                }
            }
            var mName by remember { mutableStateOf("") }
            var mUrl by remember { mutableStateOf("") }
            var mToken by remember { mutableStateOf("") }
            OutlinedTextField(mName, { mName = it }, label = { Text("名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(mUrl, { mUrl = it }, label = { Text("MCP 端點 URL") }, placeholder = { Text("https://example.com/mcp") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(mToken, { mToken = it }, label = { Text("Bearer Token（選填）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = mUrl.startsWith("http"), onClick = {
                vm.addMcp(mName, mUrl, mToken) { mName = ""; mUrl = ""; mToken = "" }
            }) { Text("連線並新增") }

            HorizontalDivider()

            // ---------- A2A ----------
            Text("🌐 A2A 遠端代理（Agent2Agent）", style = MaterialTheme.typography.titleMedium)
            Text("輸入代理的網址，App 會讀取 /.well-known/agent-card.json，之後 Agent 可把任務委派給它。", style = MaterialTheme.typography.bodySmall)
            a2as.forEach { a ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall)
                            if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodySmall)
                            if (a.skills.isNotEmpty()) Text("技能：${a.skills.joinToString("、")}", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton({ vm.deleteA2A(a.id) }) { Icon(Icons.Default.Delete, "刪除") }
                    }
                }
            }
            var aUrl by remember { mutableStateOf("") }
            var aToken by remember { mutableStateOf("") }
            OutlinedTextField(aUrl, { aUrl = it }, label = { Text("A2A 代理網址") }, placeholder = { Text("https://agent.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(aToken, { aToken = it }, label = { Text("Bearer Token（選填）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = aUrl.startsWith("http"), onClick = { vm.addA2A(aUrl, aToken) { aUrl = ""; aToken = "" } }) {
                Text("讀取 Agent Card 並新增")
            }
        }
    }
}
