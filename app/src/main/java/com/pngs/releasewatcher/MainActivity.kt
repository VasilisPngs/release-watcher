package com.pngs.releasewatcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.max

private enum class Screen {
    Login,
    Settings
}

private data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int
)

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            var screen by remember { mutableStateOf(Screen.Login) }
            var message by remember { mutableStateOf("Not signed in") }
            var userCode by remember { mutableStateOf<String?>(null) }
            var verificationUri by remember { mutableStateOf<String?>(null) }
            var signingIn by remember { mutableStateOf(false) }

            ReleaseWatcherApp(
                screen = screen,
                message = message,
                userCode = userCode,
                verificationUri = verificationUri,
                signingIn = signingIn,
                onSettingsClick = { screen = Screen.Settings },
                onBackClick = { screen = Screen.Login },
                onOpenVerification = {
                    verificationUri?.let { openUrl(it) }
                },
                onSignInClick = {
                    signingIn = true
                    message = "Requesting GitHub device code..."
                    userCode = null
                    verificationUri = null

                    startGithubLogin(
                        onCode = { code, uri ->
                            userCode = code
                            verificationUri = uri
                            message = "Enter this code on GitHub, then approve access."
                            openUrl(uri)
                        },
                        onSuccess = {
                            signingIn = false
                            userCode = null
                            verificationUri = null
                            message = "Signed in successfully."
                        },
                        onError = { error ->
                            signingIn = false
                            message = error
                        }
                    )
                }
            )
        }
    }

    private fun startGithubLogin(
        onCode: (String, String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val device = requestDeviceCode()

                mainHandler.post {
                    onCode(device.userCode, device.verificationUri)
                }

                pollForToken(device)

                mainHandler.post {
                    onSuccess()
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    onError(error.message ?: "GitHub login failed.")
                }
            }
        }
    }

    private fun requestDeviceCode(): DeviceCodeResponse {
        val json = postForm(
            url = "https://github.com/login/device/code",
            form = mapOf(
                "client_id" to GithubOAuth.CLIENT_ID
            )
        )

        val verificationUri = json.optString("verification_uri_complete")
            .ifBlank { json.getString("verification_uri") }

        return DeviceCodeResponse(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = verificationUri,
            expiresIn = json.optInt("expires_in", 900),
            interval = json.optInt("interval", 5)
        )
    }

    private fun pollForToken(device: DeviceCodeResponse) {
        var intervalSeconds = max(device.interval, 5)
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(intervalSeconds * 1000L)

            val json = postForm(
                url = "https://github.com/login/oauth/access_token",
                form = mapOf(
                    "client_id" to GithubOAuth.CLIENT_ID,
                    "device_code" to device.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                )
            )

            val token = json.optString("access_token")
            if (token.isNotBlank()) {
                return
            }

            when (json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += 5
                "access_denied" -> throw IllegalStateException("GitHub authorization was denied.")
                "expired_token" -> throw IllegalStateException("GitHub login code expired.")
                else -> throw IllegalStateException(json.optString("error_description", "GitHub login failed."))
            }
        }

        throw IllegalStateException("GitHub login expired.")
    }

    private fun postForm(
        url: String,
        form: Map<String, String>
    ): JSONObject {
        val body = form.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }

        val connection = URL(url).openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("User-Agent", "ReleaseWatcher")

        connection.outputStream.use {
            it.write(body.toByteArray(Charsets.UTF_8))
        }

        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val response = stream.bufferedReader().use {
            it.readText()
        }

        connection.disconnect()

        return JSONObject(response)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

@Composable
private fun ReleaseWatcherApp(
    screen: Screen,
    message: String,
    userCode: String?,
    verificationUri: String?,
    signingIn: Boolean,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    onOpenVerification: () -> Unit,
    onSignInClick: () -> Unit
) {
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
            when (screen) {
                Screen.Login -> LoginScreen(
                    message = message,
                    userCode = userCode,
                    verificationUri = verificationUri,
                    signingIn = signingIn,
                    onSettingsClick = onSettingsClick,
                    onOpenVerification = onOpenVerification,
                    onSignInClick = onSignInClick
                )

                Screen.Settings -> SettingsScreen(
                    onBackClick = onBackClick
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    message: String,
    userCode: String?,
    verificationUri: String?,
    signingIn: Boolean,
    onSettingsClick: () -> Unit,
    onOpenVerification: () -> Unit,
    onSignInClick: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Release Watcher",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onSettingsClick) {
                    Text(
                        text = "⚙",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Track releases from your starred GitHub repositories.",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
                    )

                    Button(
                        onClick = onSignInClick,
                        enabled = !signingIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (signingIn) "Waiting for GitHub..." else "Sign in with GitHub")
                    }

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )

                    if (userCode != null && verificationUri != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = userCode,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = "Enter this code on GitHub to finish signing in.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                                )

                                OutlinedButton(
                                    onClick = onOpenVerification,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Open GitHub")
                                }
                            }
                        }
                    }

                    Text(
                        text = "Read-only access. No write actions. No private repositories by default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    onBackClick: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Text(
                        text = "‹",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(
                        text = "Release filters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    SettingRow("Stable releases", true)
                    SettingRow("Prereleases", false)
                    SettingRow("Beta releases", false)
                    SettingRow("Alpha releases", false)
                    SettingRow("Dev / Nightly releases", false)
                    SettingRow("Release candidates", false)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    checked: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )

        Switch(
            checked = checked,
            onCheckedChange = {}
        )
    }
}

private object GithubOAuth {
    const val CLIENT_ID = "Ov23liAh9Q6Ihf7Tgen8"
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
