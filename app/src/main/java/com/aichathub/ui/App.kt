package com.aichathub.ui

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class Screen { CHAT, PROVIDERS, EDIT_PROVIDER, AGENTS, EDIT_AGENT, TOOLS }

@Composable
fun App(vm: ChatViewModel) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    LaunchedEffect(Unit) { vm.toast.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }

    MaterialTheme(colorScheme = colors) {
        var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
        BackHandler(enabled = screen != Screen.CHAT) {
            screen = when (screen) {
                Screen.EDIT_PROVIDER -> Screen.PROVIDERS
                Screen.EDIT_AGENT -> Screen.AGENTS
                else -> Screen.CHAT
            }
        }
        when (screen) {
            Screen.CHAT -> ChatScreen(
                vm,
                openProviders = { screen = Screen.PROVIDERS },
                openAgents = { screen = Screen.AGENTS },
                openTools = { screen = Screen.TOOLS },
            )
            Screen.AGENTS -> AgentsScreen(
                vm,
                onBack = { screen = Screen.CHAT },
                onEdit = { vm.editingAgent.value = it; screen = Screen.EDIT_AGENT },
            )
            Screen.EDIT_AGENT -> EditAgentScreen(vm, onBack = { screen = Screen.AGENTS })
            Screen.TOOLS -> ToolsScreen(vm, onBack = { screen = Screen.CHAT })
            Screen.PROVIDERS -> ProvidersScreen(
                vm,
                onBack = { screen = Screen.CHAT },
                onEdit = { vm.editing.value = it; screen = Screen.EDIT_PROVIDER },
            )
            Screen.EDIT_PROVIDER -> EditProviderScreen(vm, onBack = { screen = Screen.PROVIDERS })
        }
    }
}
