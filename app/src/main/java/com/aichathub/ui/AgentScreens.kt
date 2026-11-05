package com.aichathub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.dp
import com.aichathub.data.AgentProfile
import com.aichathub.data.BuiltinTools

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(vm: ChatViewModel, onBack: () -> Unit, onEdit: (AgentProfile) -> Unit) {
    val agents by vm.agents.collectAsState()
    val providers by vm.providers.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents 與多代理團隊") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton({ onEdit(AgentProfile(name = "新 Agent")) }) { Icon(Icons.Default.Add, "新增") }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "在對話頂端的選單選擇 Agent 即可啟用 Agent 模式。Agent 能上網搜尋、讀網頁、使用 MCP 工具、呼叫 A2A 遠端代理，" +
                        "並可把任務委派給「團隊成員」形成多代理協作。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(agents, key = { it.id }) { a ->
                Card(Modifier.fillMaxWidth().clickable { onEdit(a) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("🤖 ${a.name}", style = MaterialTheme.typography.titleMedium)
                        if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodySmall)
                        val prov = providers.find { it.id == a.providerId }?.name ?: "跟隨對話"
                        val parts = buildList {
                            add("服務：$prov${if (a.model.isNotBlank()) " / ${a.model}" else ""}")
                            if (a.builtinTools.isNotEmpty()) add("工具 ${a.builtinTools.size}")
                            if (a.mcpServerIds.isNotEmpty()) add("MCP ${a.mcpServerIds.size}")
                            if (a.a2aAgentIds.isNotEmpty()) add("A2A ${a.a2aAgentIds.size}")
                            if (a.subAgentIds.isNotEmpty()) add("👥 成員 ${a.subAgentIds.size}")
                        }
                        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditAgentScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val original by vm.editingAgent.collectAsState()
    val base = original ?: run { onBack(); return }
    val providers by vm.providers.collectAsState()
    val agents by vm.agents.collectAsState()
    val mcps by vm.mcpServers.collectAsState()
    val a2as by vm.a2aAgents.collectAsState()

    var name by remember(base.id) { mutableStateOf(base.name) }
    var desc by remember(base.id) { mutableStateOf(base.description) }
    var providerId by remember(base.id) { mutableStateOf(base.providerId) }
    var model by remember(base.id) { mutableStateOf(base.model) }
    var prompt by remember(base.id) { mutableStateOf(base.systemPrompt) }
    var builtins by remember(base.id) { mutableStateOf(base.builtinTools.toSet()) }
    var mcpIds by remember(base.id) { mutableStateOf(base.mcpServerIds.toSet()) }
    var a2aIds by remember(base.id) { mutableStateOf(base.a2aAgentIds.toSet()) }
    var subIds by remember(base.id) { mutableStateOf(base.subAgentIds.toSet()) }
    var steps by remember(base.id) { mutableStateOf(base.maxSteps.toString()) }
    val exists = agents.any { it.id == base.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (exists) "編輯 Agent" else "新增 Agent") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text("職責說明（給其他 Agent 看，用於分派任務）") }, modifier = Modifier.fillMaxWidth())

            Text("AI 服務", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = providerId == null, onClick = { providerId = null }, label = { Text("跟隨對話") })
                providers.forEach { p -> FilterChip(selected = providerId == p.id, onClick = { providerId = p.id }, label = { Text(p.name) }) }
            }
            val prov = providers.find { it.id == providerId }
            OutlinedTextField(model, { model = it }, label = { Text("模型（留空 = 預設）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (prov != null && prov.models.isNotEmpty()) {
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    prov.models.take(200).forEach { m ->
                        Text(m, Modifier.fillMaxWidth().clickable { model = m }.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (m == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            OutlinedTextField(prompt, { prompt = it }, label = { Text("系統提示詞（角色與工作方式）") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))

            Text("內建工具", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BuiltinTools.all.forEach { (id, label) ->
                    FilterChip(selected = id in builtins, onClick = { builtins = builtins.toggle(id) }, label = { Text(label) })
                }
            }

            Text("MCP 伺服器", style = MaterialTheme.typography.labelLarge)
            if (mcps.isEmpty()) Text("尚未新增，請到「工具：搜尋 / MCP / A2A」新增", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mcps.forEach { m -> FilterChip(selected = m.id in mcpIds, onClick = { mcpIds = mcpIds.toggle(m.id) }, label = { Text("${m.name}（${m.toolCount}）") }) }
            }

            Text("A2A 遠端代理", style = MaterialTheme.typography.labelLarge)
            if (a2as.isEmpty()) Text("尚未新增，請到「工具：搜尋 / MCP / A2A」新增", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a2as.forEach { a -> FilterChip(selected = a.id in a2aIds, onClick = { a2aIds = a2aIds.toggle(a.id) }, label = { Text(a.name) }) }
            }

            Text("團隊成員（可委派任務的 Agent）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                agents.filter { it.id != base.id }.forEach { a ->
                    FilterChip(selected = a.id in subIds, onClick = { subIds = subIds.toggle(a.id) }, label = { Text(a.name) })
                }
            }

            OutlinedTextField(steps, { steps = it.filter(Char::isDigit).take(2) }, label = { Text("最多工具步驟數") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = name.isNotBlank(), onClick = {
                    vm.saveAgent(base.copy(
                        name = name.trim(), description = desc.trim(), providerId = providerId, model = model.trim(),
                        systemPrompt = prompt, builtinTools = builtins.toList(), mcpServerIds = mcpIds.toList(),
                        a2aAgentIds = a2aIds.toList(), subAgentIds = subIds.toList(),
                        maxSteps = steps.toIntOrNull()?.coerceIn(1, 30) ?: 10,
                    ))
                    onBack()
                }) { Text("儲存") }
                if (exists) {
                    OutlinedButton({ vm.selectAgent(base.id); onBack() }) { Text("在目前對話使用") }
                    TextButton({ vm.deleteAgent(base.id); onBack() }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id
