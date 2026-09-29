package com.lyra.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lyra.music.data.model.Song
import com.lyra.music.ui.theme.LyraColors
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

private val DIACRITICS = Regex("\\p{Mn}+")

/** Minúsculas y sin tildes: "Canción" y "cancion" se encuentran igual. */
fun searchKey(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

/** Palabras de una búsqueda ya normalizadas. */
fun queryWords(query: String): List<String> = searchKey(query).split(' ').filter { it.isNotBlank() }

/** true si todas las palabras aparecen en alguno de los textos ("bunny titi" encuentra "Tití Me Preguntó · Bad Bunny"). */
fun matchesQuery(query: String, vararg texts: String?): Boolean {
    val words = queryWords(query)
    if (words.isEmpty()) return true
    val haystack = texts.filterNotNull().joinToString(" ") { searchKey(it) }
    return words.all { it in haystack }
}

/** Texto de búsqueda de una canción (título, artistas y álbum). */
fun Song.searchText(): String = searchKey("$title $artistsText ${album?.title.orEmpty()}")

/** Orden de una lista de canciones; [DEFAULT] es el propio de cada lista (recientes, tu orden…). */
enum class SongOrder(val label: String?) {
    DEFAULT(null),
    TITLE("Título"),
    ARTIST("Artista"),
    ALBUM("Álbum"),
}

private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }

/** Filtra por texto y ordena, conservando el tipo de fila ([song] saca la canción de cada una). */
fun <T> List<T>.filterSongs(query: String, order: SongOrder, song: (T) -> Song): List<T> {
    val words = queryWords(query)
    val filtered = if (words.isEmpty()) this else filter { row -> song(row).searchText().let { text -> words.all { it in text } } }
    val comparator: Comparator<T> = when (order) {
        SongOrder.DEFAULT -> return filtered
        SongOrder.TITLE -> compareBy(collator) { song(it).title }
        SongOrder.ARTIST -> compareBy<T, String>(collator) { song(it).artistsText }.thenBy(collator) { song(it).title }
        SongOrder.ALBUM -> compareBy<T, String?>(nullsLast(collator)) { song(it).album?.title }.thenBy(collator) { song(it).title }
    }
    return filtered.sortedWith(comparator)
}

/** Campo de búsqueda en forma de píldora (filtra al escribir, sin botón de enviar). */
@Composable
fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
) {
    val focus = LocalFocusManager.current
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(LyraColors.Surface)
            .border(1.dp, LyraColors.Border, RoundedCornerShape(50))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, color = LyraColors.TextTertiary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LyraColors.TextPrimary),
                cursorBrush = SolidColor(LyraColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Borrar", tint = LyraColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** Buscar dentro de una lista y cambiar su orden (Me gusta, Descargas, playlists). */
@Composable
fun SongFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    order: SongOrder,
    onOrderChange: (SongOrder) -> Unit,
    defaultOrderLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchPill(query, onQueryChange, "Buscar en la lista", Modifier.weight(1f), height = 42.dp)
        Spacer(Modifier.width(10.dp))
        Box {
            var open by remember { mutableStateOf(false) }
            val custom = order != SongOrder.DEFAULT
            Row(
                Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (custom) LyraColors.Accent else LyraColors.Surface)
                    .border(1.dp, if (custom) LyraColors.Accent else LyraColors.Border, RoundedCornerShape(50))
                    .pressable { open = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (custom) LyraColors.OnAccent else LyraColors.TextPrimary
                Icon(Icons.AutoMirrored.Rounded.Sort, "Ordenar", tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(order.label ?: defaultOrderLabel, style = MaterialTheme.typography.labelLarge, color = tint)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LyraColors.SurfaceHigh) {
                SongOrder.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label ?: defaultOrderLabel) },
                        trailingIcon = if (option == order) {
                            { Icon(Icons.Rounded.Check, null, tint = LyraColors.Accent, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            onOrderChange(option)
                        },
                    )
                }
            }
        }
    }
}
