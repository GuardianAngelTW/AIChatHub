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

enum class Screen { CHAT, PROVIDERS, EDIT_PROVIDER }

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
            screen = if (screen == Screen.EDIT_PROVIDER) Screen.PROVIDERS else Screen.CHAT
        }
        when (screen) {
            Screen.CHAT -> ChatScreen(vm, openProviders = { screen = Screen.PROVIDERS })
            Screen.PROVIDERS -> ProvidersScreen(
                vm,
                onBack = { screen = Screen.CHAT },
                onEdit = { vm.editing.value = it; screen = Screen.EDIT_PROVIDER },
            )
            Screen.EDIT_PROVIDER -> EditProviderScreen(vm, onBack = { screen = Screen.PROVIDERS })
        }
    }
}
