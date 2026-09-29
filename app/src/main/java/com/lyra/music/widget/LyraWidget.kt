package com.lyra.music.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.lyra.music.LyraApp
import com.lyra.music.MainActivity
import com.lyra.music.R
import com.lyra.music.data.model.Song
import com.lyra.music.playback.QueueStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.min

// Colores de Lira' (el widget no puede usar el tema de la app).
private val Surface = Color(0xFF141416)
private val SurfaceHigher = Color(0xFF26262A)
private val Fg = Color(0xFFF3F3F1)
private val Muted = Color(0xFF9C9C96)
private val Bone = Color(0xFFE8E6DF)
private val Ink = Color(0xFF0A0A0B)

private val TITLE = stringPreferencesKey("title")
private val ARTIST = stringPreferencesKey("artist")
private val COVER = stringPreferencesKey("cover")
private val PLAYING = booleanPreferencesKey("playing")
private val COMMAND = ActionParameters.Key<String>("command")

private data class NowPlaying(val title: String?, val artist: String?, val cover: String?, val playing: Boolean)

/** Widget de pantalla de inicio: lo que suena, con su carátula y los controles. */
class LyraWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, BAR, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val state = NowPlaying(prefs[TITLE], prefs[ARTIST], prefs[COVER], prefs[PLAYING] == true)
            val cover = remember(state.cover) {
                state.cover?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
            }
            val size = LocalSize.current
            when {
                size.width >= WIDE.width && size.height >= WIDE.height -> Wide(state, cover)
                size.width >= BAR.width -> Bar(state, cover)
                else -> Small(state, cover)
            }
        }
    }

    companion object {
        val SMALL = DpSize(100.dp, 100.dp)
        val BAR = DpSize(220.dp, 56.dp)
        val WIDE = DpSize(220.dp, 116.dp)
    }
}

@UnstableApi
class LyraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LyraWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Recién añadido (o tras reiniciar el móvil): que muestre ya lo que suena.
        val app = context.applicationContext
        (app as LyraApp).container.scope.launch { runCatching { WidgetUpdater.refresh(app) } }
    }
}

// ------------------------------------------------------------------ diseños

@Composable
private fun Wide(state: NowPlaying, cover: Bitmap?) {
    Row(
        GlanceModifier.fillMaxSize().container().padding(12.dp).clickable(openPlayer()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, 92.dp, 16.dp)
        Spacer(GlanceModifier.width(14.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text("Lyra", style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp, fontFamily = FontFamily.Serif))
            Text(
                state.title ?: "Nada sonando",
                style = TextStyle(color = ColorProvider(Fg), fontSize = 17.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                state.artist ?: "Toca para abrir Lyra",
                style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(8.dp))
            Controls(state.playing, button = 34.dp, play = 42.dp)
        }
    }
}

@Composable
private fun Bar(state: NowPlaying, cover: Bitmap?) {
    Row(
        GlanceModifier.fillMaxSize().container().padding(horizontal = 10.dp, vertical = 8.dp).clickable(openPlayer()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, 40.dp, 10.dp)
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                state.title ?: "Lyra",
                style = TextStyle(color = ColorProvider(Fg), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                state.artist ?: "Nada sonando",
                style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp),
                maxLines = 1,
            )
        }
        Controls(state.playing, button = 32.dp, play = 36.dp)
    }
}

@Composable
private fun Small(state: NowPlaying, cover: Bitmap?) {
    Box(
        GlanceModifier.fillMaxSize().container().clickable(openPlayer()),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (cover != null) {
            Image(ImageProvider(cover), state.title, GlanceModifier.fillMaxSize().cornerRadius(22.dp), contentScale = ContentScale.Crop)
        } else {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.ic_widget_note), null, GlanceModifier.size(30.dp), colorFilter = ColorFilter.tint(ColorProvider(Muted)))
            }
        }
        Box(GlanceModifier.padding(8.dp)) { PlayButton(state.playing, 40.dp) }
    }
}

// ------------------------------------------------------------------ piezas

private fun GlanceModifier.container() = appWidgetBackground().background(ImageProvider(R.drawable.widget_bg)).cornerRadius(22.dp)

@Composable
private fun Cover(cover: Bitmap?, size: Dp, radius: Dp) {
    if (cover != null) {
        Image(ImageProvider(cover), null, GlanceModifier.size(size).cornerRadius(radius), contentScale = ContentScale.Crop)
    } else {
        Box(
            GlanceModifier.size(size).background(ColorProvider(SurfaceHigher)).cornerRadius(radius),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.ic_widget_note), null, GlanceModifier.size(size * 0.4f), colorFilter = ColorFilter.tint(ColorProvider(Muted)))
        }
    }
}

@Composable
private fun Controls(playing: Boolean, button: Dp, play: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ControlIcon(R.drawable.ic_widget_prev, "Anterior", WidgetControl.PREVIOUS, button)
        Spacer(GlanceModifier.width(4.dp))
        PlayButton(playing, play)
        Spacer(GlanceModifier.width(4.dp))
        ControlIcon(R.drawable.ic_widget_next, "Siguiente", WidgetControl.NEXT, button)
    }
}

@Composable
private fun ControlIcon(icon: Int, description: String, command: String, size: Dp) {
    Box(GlanceModifier.size(size).cornerRadius(size / 2).clickable(control(command)), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), description, GlanceModifier.size(size * 0.62f), colorFilter = ColorFilter.tint(ColorProvider(Fg)))
    }
}

@Composable
private fun PlayButton(playing: Boolean, size: Dp) {
    Box(
        GlanceModifier.size(size).background(ImageProvider(R.drawable.widget_play_bg)).cornerRadius(size / 2)
            .clickable(control(WidgetControl.TOGGLE)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            if (playing) "Pausar" else "Reproducir",
            GlanceModifier.size(size * 0.5f),
            colorFilter = ColorFilter.tint(ColorProvider(Ink)),
        )
    }
}

@Composable
private fun openPlayer() = actionStartActivity(
    Intent(LocalContext.current, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER),
)

private fun control(command: String) = actionRunCallback<WidgetControl>(actionParametersOf(COMMAND to command))

// ------------------------------------------------------------------ acciones y estado

/** Botones del widget: hablan con el servicio de música aunque la app esté cerrada. */
@UnstableApi
class WidgetControl : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = parameters[COMMAND] ?: return
        val container = (context.applicationContext as LyraApp).container
        if (command == TOGGLE) {
            // El icono cambia al momento; el servicio lo corrige si hiciera falta.
            updateAppWidgetState(context, glanceId) { it[PLAYING] = it[PLAYING] != true }
            LyraWidget().update(context, glanceId)
        }
        withTimeoutOrNull(8_000) {
            container.player.withController { c ->
                when (command) {
                    TOGGLE -> {
                        if (c.playbackState == Player.STATE_IDLE) c.prepare()
                        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                        if (c.playWhenReady) c.pause() else c.play()
                    }
                    NEXT -> c.seekToNext()
                    PREVIOUS -> if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
                }
            }
        }
    }

    companion object {
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
    }
}

/** El servicio de música avisa aquí cada vez que cambia la canción o se pausa. */
@UnstableApi
object WidgetUpdater {

    /** Lo que suena ahora o, si Lyra está cerrada, lo último que sonó (en pausa). */
    suspend fun refresh(context: Context) {
        val player = (context.applicationContext as LyraApp).container.player
        val state = player.state.value
        if (state.song != null) {
            push(context, state.song, player.currentMediaItem?.mediaMetadata?.artworkUri, state.isPlaying)
            return
        }
        val last = withContext(Dispatchers.IO) {
            val saved = QueueStore(File(context.filesDir, "queue.json"), (context.applicationContext as LyraApp).container.scope).load()
            saved?.songs?.getOrNull(saved.index)
        }
        push(context, last, null, false)
    }

    suspend fun push(context: Context, song: Song?, artwork: Uri?, playing: Boolean) {
        val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(LyraWidget::class.java) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        val cover = song?.let { runCatching { coverFile(context, it, artwork) }.getOrNull() }
        ids.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                if (song == null) {
                    prefs.remove(TITLE)
                    prefs.remove(ARTIST)
                    prefs.remove(COVER)
                } else {
                    prefs[TITLE] = song.title
                    prefs[ARTIST] = song.artistsText
                    if (cover != null) prefs[COVER] = cover else prefs.remove(COVER)
                }
                prefs[PLAYING] = playing
            }
            LyraWidget().update(context, id)
        }
    }

    /** El widget no puede cargar URLs: la carátula se guarda cuadrada y pequeña en un archivo. */
    private suspend fun coverFile(context: Context, song: Song, artwork: Uri?): String? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "widget").apply { mkdirs() }
        val file = File(dir, "cover_${Integer.toHexString(song.id.hashCode())}.jpg")
        if (file.length() > 0) return@withContext file.absolutePath
        val model: Any = artwork ?: song.thumbnailUrl ?: return@withContext null
        val request = ImageRequest.Builder(context).data(model).size(320).allowHardware(false).build()
        val loaded = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@withContext null
        val side = min(loaded.width, loaded.height)
        val square = Bitmap.createBitmap(loaded, (loaded.width - side) / 2, (loaded.height - side) / 2, side, side)
        val small = if (side > 320) Bitmap.createScaledBitmap(square, 320, 320, true) else square
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.absolutePath
    }
}
