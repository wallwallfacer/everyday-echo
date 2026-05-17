package com.everydayecho.memorykeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.everydayecho.memorykeeper.ui.LetterScreen
import com.everydayecho.memorykeeper.ui.MemoryScreen
import com.everydayecho.memorykeeper.ui.OnboardingScreen
import com.everydayecho.memorykeeper.ui.SettingsScreen
import com.everydayecho.memorykeeper.ui.theme.MemoryKeeperTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MemoryKeeperTheme {
                AppRoot()
            }
        }
    }
}

private enum class Screen { Loading, Onboarding, Letter, Settings, Memory }

@androidx.compose.runtime.Composable
private fun AppRoot() {
    val app = MemoryKeeperApp.get()
    var screen by remember { mutableStateOf(Screen.Loading) }

    LaunchedEffect(Unit) {
        val profile = app.repository.loadProfile()
        screen = if (profile.onboardingCompleted) Screen.Letter else Screen.Onboarding
    }

    when (screen) {
        Screen.Loading -> Unit
        Screen.Onboarding -> OnboardingScreen(onDone = { screen = Screen.Letter })
        Screen.Letter -> LetterScreen(
            onOpenSettings = { screen = Screen.Settings },
            onOpenMemory = { screen = Screen.Memory },
        )
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Letter })
        Screen.Memory -> MemoryScreen(onBack = { screen = Screen.Letter })
    }
}
