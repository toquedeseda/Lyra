package com.lyra.desktop.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.Screen
import com.lyra.desktop.ui.components.ItemCard
import com.lyra.desktop.ui.components.MessageView
import com.lyra.desktop.ui.components.SectionHeader
import com.lyra.desktop.ui.components.SongRow
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.Section
import com.lyra.music.data.model.SectionStyle
import com.lyra.music.data.model.Song

/** Margen lateral de las pantallas. */
val PagePadding = 28.dp

/** Lista de la pantalla central con su barra de desplazamiento. */
@Composable
fun ScreenList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 40.dp),
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = contentPadding, modifier = Modifier.fillMaxSize(), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp).padding(end = 2.dp))
    }
}

/**
 * Cabecera de álbum, playlist o artista, como en Spotify: portada grande con sombra, el tipo,
 * el título grande con la letra de Lyra y debajo los datos. Con un degradado suave detrás.
 */
@Composable
fun CollectionHeader(
    type: String,
    title: String,
    cover: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    meta: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(LyraColors.SurfaceHigher, LyraColors.Surface))),
    ) {
        Row(Modifier.padding(start = PagePadding, end = PagePadding, top = 12.dp, bottom = 24.dp), verticalAlignment = Alignment.Bottom) {
            cover(Modifier.size(212.dp).shadow(22.dp, RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(type, style = MaterialTheme.typography.labelMedium, color = LyraColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                BoxWithConstraints {
                    // Títulos largos, más pequeños (como hace Spotify).
                    val size = when {
                        title.length > 40 -> 40.sp
                        title.length > 22 -> 54.sp
                        else -> 72.sp
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = size, lineHeight = size),
                        color = LyraColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, content = meta)
            }
        }
    }
}

/** Punto separador de los datos de una cabecera. */
@Composable
fun MetaDot() = Text("  ·  ", style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)

@Composable
fun MetaText(text: String, strong: Boolean = false) =
    Text(text, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, color = if (strong) LyraColors.TextPrimary else LyraColors.TextSecondary, maxLines = 1)

/** Fila de botones bajo la cabecera (reproducir, aleatorio, guardar…). */
@Composable
fun ActionBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PagePadding, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Títulos de la tabla de canciones. */
@Composable
fun SongTableHeader(showAlbum: Boolean, showCover: Boolean = true) {
    Column(Modifier.padding(horizontal = PagePadding - 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary, modifier = Modifier.width(40.dp))
            if (showCover) Spacer(Modifier.width(52.dp))
            Text("Título", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary, modifier = Modifier.weight(1f))
            if (showAlbum) Text("Álbum", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary, modifier = Modifier.weight(0.7f).padding(horizontal = 12.dp))
            Spacer(Modifier.width(36.dp))
            Icon(Icons.Outlined.Schedule, "Duración", tint = LyraColors.TextSecondary, modifier = Modifier.width(48.dp).size(16.dp).padding(start = 6.dp))
            Spacer(Modifier.width(36.dp))
        }
        HorizontalDivider(color = LyraColors.Border)
        Spacer(Modifier.height(8.dp))
    }
}

/** Canciones de una lista, con su número (para álbumes y playlists). */
fun LazyListScope.songItems(
    songs: List<Song>,
    keyPrefix: String,
    showAlbum: Boolean = true,
    showCover: Boolean = true,
    onPlay: (Int) -> Unit,
    menuExtra: ((Song) -> (@Composable ColumnScope.(close: () -> Unit) -> Unit))? = null,
) {
    items(songs.size, key = { "$keyPrefix-$it-${songs[it].id}" }) { index ->
        val song = songs[index]
        SongRow(
            song,
            onPlay = { onPlay(index) },
            modifier = Modifier.padding(horizontal = PagePadding - 12.dp),
            index = index + 1,
            showCover = showCover,
            showAlbum = showAlbum,
            menuExtra = menuExtra?.invoke(song),
        )
    }
}

/** Una fila de tarjetas: las que quepan a lo ancho (como en Spotify). */
@Composable
fun CardsRow(items: List<MusicItem>, modifier: Modifier = Modifier, maxRows: Int = 1) {
    val actions = LocalActions.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardWidth = cardWidthFor(maxWidth)
        val perRow = ((maxWidth + 8.dp) / (cardWidth + 8.dp)).toInt().coerceAtLeast(2)
        Column {
            items.take(perRow * maxRows).chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        ItemCard(item, onOpen = { actions.open(item) }, onPlay = { actions.playItem(item) }, width = cardWidth)
                    }
                }
            }
        }
    }
}

/** Todas las tarjetas, en rejilla. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardsGrid(items: List<MusicItem>, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardWidth = cardWidthFor(maxWidth)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                ItemCard(item, onOpen = { actions.open(item) }, onPlay = { actions.playItem(item) }, width = cardWidth)
            }
        }
    }
}

private fun cardWidthFor(available: Dp): Dp {
    // Entre 160 y 210 de ancho, repartiendo el espacio sin huecos raros.
    val count = ((available + 8.dp) / (188.dp)).toInt().coerceAtLeast(2)
    return ((available - 8.dp * (count - 1)) / count).coerceIn(150.dp, 230.dp)
}

/** Canciones en columnas (las «selecciones rápidas» de YouTube Music). */
@Composable
fun SongsColumns(songs: List<Song>, rows: Int = 4) {
    val actions = LocalActions.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth > 1100.dp -> 3
            maxWidth > 640.dp -> 2
            else -> 1
        }
        val visible = songs.take(rows * columns)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            visible.chunked(rows).forEach { column ->
                Column(Modifier.weight(1f)) {
                    column.forEach { song ->
                        SongRow(song, onPlay = { actions.play(songs, songs.indexOf(song)) })
                    }
                }
            }
            repeat(columns - visible.chunked(rows).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Una sección de YouTube Music (fila de tarjetas o canciones en columnas). */
@Composable
fun SectionView(section: Section, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val songsOnly = section.items.isNotEmpty() && section.items.all { it is Song }
    Column(modifier.padding(horizontal = PagePadding - 10.dp, vertical = 14.dp)) {
        SectionHeader(
            section.title,
            Modifier.padding(horizontal = 10.dp),
            subtitle = section.subtitle,
            onMore = when {
                section.more != null -> { { actions.nav.navigate(Screen.Browse(section.more!!, section.title)) } }
                section.items.size > 6 -> { { actions.nav.navigate(Screen.AllOfSection(section)) } }
                else -> null
            },
        )
        if (songsOnly && (section.style == SectionStyle.SONG_GRID || section.style == SectionStyle.LIST)) {
            SongsColumns(section.items.filterIsInstance<Song>())
        } else {
            CardsRow(section.items)
        }
    }
}

@Composable
fun ErrorView(message: String?, onRetry: () -> Unit) {
    MessageView(Icons.Rounded.CloudOff, "No se pudo cargar", message ?: "Comprueba la conexión y vuelve a intentarlo.", action = "Reintentar", onAction = onRetry)
}

/** Lo que tarda una lista entera («1 h 20 min»). */
fun totalDuration(songs: List<Song>): String {
    val ms = songs.sumOf { it.durationMs ?: 0L }
    if (ms <= 0) return ""
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

fun songCount(n: Int) = if (n == 1) "1 canción" else "$n canciones"

/** Lo que tarda en cargar una pantalla. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String?) : Load<Nothing>
}

/** Páginas ya cargadas: al volver atrás salen al instante (las 80 últimas). */
object PageCache {
    private val map = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 80
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(key: String): T? = map[key] as T?

    @Synchronized
    fun put(key: String, value: Any) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)
    }
}

/** Carga [key] (o la saca de la caché). Devuelve el estado y una función para reintentar. */
@Composable
fun <T : Any> rememberLoad(key: String, load: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var attempt by androidx.compose.runtime.remember(key) { androidx.compose.runtime.mutableIntStateOf(0) }
    val cached = androidx.compose.runtime.remember(key) { PageCache.get<T>(key) }
    val state = androidx.compose.runtime.produceState<Load<T>>(if (cached != null) Load.Ready(cached) else Load.Loading, key, attempt) {
        if (attempt == 0 && cached != null) return@produceState
        if (value !is Load.Ready) value = Load.Loading
        value = try {
            val result = load()
            PageCache.put(key, result)
            Load.Ready(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message)
        }
    }
    return state.value to { attempt++ }
}
