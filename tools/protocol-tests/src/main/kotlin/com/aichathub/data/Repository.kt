package com.aichathub.data
import kotlinx.coroutines.flow.MutableStateFlow
// JVM stand-in exposing the members AgentRunner uses (test only)
class Repository {
    val providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val agents = MutableStateFlow<List<AgentProfile>>(emptyList())
    val mcpServers = MutableStateFlow<List<McpServer>>(emptyList())
    val a2aAgents = MutableStateFlow<List<A2AAgent>>(emptyList())
    val search = MutableStateFlow(SearchConfig())
    val keys = mutableMapOf<String, String>()
    fun apiKey(id: String) = keys[id]
    fun mcpToken(id: String) = keys["mcp:$id"]
    fun a2aToken(id: String) = keys["a2a:$id"]
    fun searchKey(e: SearchEngine) = keys["search:${e.name}"]
}
