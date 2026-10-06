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
import android.app.SearchManager
import android.provider.MediaStore

@UnstableApi
class MainActivity : ComponentActivity() {

    private val container get() = (application as LyraApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Al entrar, el logo crece un poco y se desvanece (rápido: el Inicio ya está listo detrás).
        splash.setOnExitAnimationListener { provider ->
            provider.iconView.animate()
                .scaleX(1.2f).scaleY(1.2f).alpha(0f)
                .setDuration(220L)
                .withEndAction { provider.remove() }
                .start()
            provider.view.animate().alpha(0f).setDuration(220L).start()
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // El reproductor se conecta justo después de dibujar la primera pantalla (ver LyraRoot): al
        // conectarse arranca el servicio de música, que recupera la cola, y eso retrasaba el Inicio.
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
            ACTION_OPEN_UPDATE -> container.send(AppEvent.OpenUpdate)
            ACTION_UPDATE_NOW -> container.send(AppEvent.UpdateNow)
            ACTION_OPEN_ALBUM -> intent.getStringExtra(EXTRA_ID)?.let { container.send(AppEvent.OpenAlbum(it)) }
            ACTION_OPEN_PLAYLIST -> intent.getStringExtra(EXTRA_ID)?.toLongOrNull()?.let { container.send(AppEvent.OpenLocalPlaylist(it)) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                Regex("https?://\\S+").find(text)?.value?.let { container.send(AppEvent.OpenLink(it)) }
            }
            Intent.ACTION_VIEW -> intent.dataString?.let { container.send(AppEvent.OpenLink(it)) }
            // "Ok Google, pon … en Lyra": lo resuelve el servicio igual que la voz del coche.
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ->
                container.player.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty(), intent.extras)
        }
    }

    private fun requestNotificationPermission() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // La carpeta Música/Lyra en Android 8-9 necesita el permiso de almacenamiento.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.lyra.music.OPEN_PLAYER"
        const val ACTION_OPEN_DOWNLOADS = "com.lyra.music.OPEN_DOWNLOADS"
        const val ACTION_OPEN_UPDATE = "com.lyra.music.OPEN_UPDATE"
        const val ACTION_UPDATE_NOW = "com.lyra.music.UPDATE_NOW"
        const val ACTION_OPEN_ALBUM = "com.lyra.music.OPEN_ALBUM"
        const val ACTION_OPEN_PLAYLIST = "com.lyra.music.OPEN_PLAYLIST"
        const val EXTRA_ID = "id"
    }
}
