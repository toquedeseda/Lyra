package com.lyra.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import com.lyra.music.ui.LyraRoot
import com.lyra.music.ui.theme.LyraTheme

@UnstableApi
class MainActivity : ComponentActivity() {

    private val container get() = (application as LyraApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        container.player.connect()
        requestNotificationPermission()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            LyraTheme {
                LyraRoot(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_PLAYER -> container.send(AppEvent.OpenPlayer)
            ACTION_OPEN_DOWNLOADS -> container.send(AppEvent.OpenDownloads)
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                Regex("https?://\\S+").find(text)?.value?.let { container.send(AppEvent.OpenLink(it)) }
            }
            Intent.ACTION_VIEW -> intent.dataString?.let { container.send(AppEvent.OpenLink(it)) }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.lyra.music.OPEN_PLAYER"
        const val ACTION_OPEN_DOWNLOADS = "com.lyra.music.OPEN_DOWNLOADS"
    }
}
