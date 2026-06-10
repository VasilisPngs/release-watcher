package com.pngs.releasewatcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

private enum class Screen {
    Login,
    Settings
}

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    private var screen by mutableStateOf(Screen.Login)
    private var message by mutableStateOf("Not signed in")
    private var signingIn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleOAuthCallback(intent)

        setContent {
            ReleaseWatcherApp(
                screen = screen,
                message = message,
                signingIn = signingIn,
                onSettingsClick = { screen = Screen.Settings },
                onBackClick = { screen = Screen.Login },
                onSignInClick = { startGithubLogin() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun startGithubLogin() {
        signingIn = true
        message = "Opening GitHub..."

        val verifier = randomUrlSafeString(64)
        val state = randomUrlSafeString(32)
        val challenge = codeChallenge(verifier)

        getPreferences(MODE_PRIVATE)
            .edit()
            .putString("code_verifier", verifier)
            .putString("oauth_state", state)
            .apply()

        val url = Uri.Builder()
            .scheme("https")
            .authority("github.com")
            .path("/login/oauth/authorize")
            .appendQueryParameter("client_id", GithubOAuth.CLIENT_ID)
            .appendQueryParameter("redirect_uri", GithubOAuth.REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return

        if (uri.scheme != "com.pngs.releasewatcher") return
        if (uri.host != "oauth") return
        if (uri.path != "/callback") return

        val error = uri.getQueryParameter("error")
        if (error != null) {
            signingIn = false
            message = "GitHub login cancelled."
            return
        }

        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")

        val prefs = getPreferences(MODE_PRIVATE)
        val savedState = prefs.getString("oauth_state", null)
        val verifier = prefs.getString("code_verifier", null)

        if (code.isNullOrBlank() || returnedState.isNullOrBlank() || verifier.isNullOrBlank()) {
            signingIn = false
            message = "Invalid GitHub callback."
            return
        }

        if (returnedState != savedState) {
            signingIn = false
            message = "GitHub state mismatch."
            return
        }

        signingIn = true
        message = "Finishing GitHub sign in..."

        executor.execute {
            try {
                val token = exchangeCodeForToken(code, verifier)

                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putString("access_token", token)
                    .remove("code_verifier")
                    .remove("oauth_state")
                    .apply()

                runOnUiThread {
                    signingIn = false
                    message = "Signed in successfully."
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    signingIn = false
                    message = error.message ?: "GitHub login failed."
                }
            }
        }
    }

    private fun exchangeCodeForToken(
        code: String,
        verifier: String
    ): String {
        val json = postForm(
            url = "https://github.com/login/oauth/access_token",
            form = mapOf(
                "client_id" to GithubOAuth.CLIENT_ID,
                "redirect_uri" to GithubOAuth.REDIRECT_URI,
                "code" to code,
                "code_verifier" to verifier
            )
        )

        val token = json.optString("access_token")
        if (token.isBlank()) {
            throw IllegalStateException(json.optString("error_description", "GitHub token exchange failed."))
        }

        return token
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

    private fun randomUrlSafeString(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)

        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun codeChallenge(verifier: String): String {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))

        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
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
    signingIn: Boolean,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
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
                    signingIn = signingIn,
                    onSettingsClick = onSettingsClick,
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
    signingIn: Boolean,
    onSettingsClick: () -> Unit,
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
                        Text(if (signingIn) "Signing in..." else "Sign in with GitHub")
                    }

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )

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
    const val REDIRECT_URI = "com.pngs.releasewatcher://oauth/callback"
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
