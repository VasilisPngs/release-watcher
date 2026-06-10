package com.pngs.releasewatcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            ReleaseWatcherApp()
        }
    }
}

@Composable
private fun ReleaseWatcherApp() {
    val dark = isSystemInDarkTheme()

    val colors = if (dark) {
        darkColorScheme(
            primary = GithubDark.Accent,
            background = GithubDark.Background,
            surface = GithubDark.Surface,
            surfaceVariant = GithubDark.SurfaceVariant,
            onPrimary = GithubDark.OnAccent,
            onBackground = GithubDark.Text,
            onSurface = GithubDark.Text,
            outline = GithubDark.Border
        )
    } else {
        lightColorScheme(
            primary = GithubLight.Accent,
            background = GithubLight.Background,
            surface = GithubLight.Surface,
            surfaceVariant = GithubLight.SurfaceVariant,
            onPrimary = GithubLight.OnAccent,
            onBackground = GithubLight.Text,
            onSurface = GithubLight.Text,
            outline = GithubLight.Border
        )
    }

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            color = MaterialTheme.colorScheme.background
        ) {
            HomeScreen()
        }
    }
}

@Composable
private fun HomeScreen() {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Release Watcher",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "Track releases from your starred GitHub repositories.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )

            Button(onClick = {}) {
                Text("Sign in with GitHub")
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Release filters", style = MaterialTheme.typography.titleMedium)
                    SettingRow("Stable releases", true)
                    SettingRow("Prereleases", false)
                    SettingRow("Beta releases", false)
                    SettingRow("Alpha releases", false)
                    SettingRow("Dev / Nightly releases", false)
                }
            }

            OutlinedButton(onClick = {}) {
                Text("Build preview")
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, checked: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = {}
        )
    }
}

private object GithubLight {
    val Background = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val Surface = androidx.compose.ui.graphics.Color(0xFFF6F8FA)
    val SurfaceVariant = androidx.compose.ui.graphics.Color(0xFFEAEFF4)
    val Text = androidx.compose.ui.graphics.Color(0xFF24292F)
    val Border = androidx.compose.ui.graphics.Color(0xFFD0D7DE)
    val Accent = androidx.compose.ui.graphics.Color(0xFF0969DA)
    val OnAccent = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
}

private object GithubDark {
    val Background = androidx.compose.ui.graphics.Color(0xFF0D1117)
    val Surface = androidx.compose.ui.graphics.Color(0xFF161B22)
    val SurfaceVariant = androidx.compose.ui.graphics.Color(0xFF21262D)
    val Text = androidx.compose.ui.graphics.Color(0xFFC9D1D9)
    val Border = androidx.compose.ui.graphics.Color(0xFF30363D)
    val Accent = androidx.compose.ui.graphics.Color(0xFF58A6FF)
    val OnAccent = androidx.compose.ui.graphics.Color(0xFF0D1117)
}
