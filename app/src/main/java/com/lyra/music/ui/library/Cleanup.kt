package com.lyra.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.core.ErrorLog
import com.lyra.music.data.db.StaleDownload
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.components.GhostPillButton
import com.lyra.music.ui.components.InfoCard
import com.lyra.music.ui.components.PillButton
import com.lyra.music.ui.theme.LyraColors

fun monthsText(months: Int) = when (months) {
    1 -> "1 mes"
    12 -> "1 año"
    else -> "$months meses"
}

/** Aviso en Descargas: canciones descargadas que llevas meses sin escuchar. */
@Composable
fun CleanupCard(stale: List<StaleDownload>, months: Int, onReview: () -> Unit) {
    val bytes = stale.sumOf { it.totalBytes }
    InfoCard(
        title = "Libera ${formatBytes(bytes)}",
        text = (if (stale.size == 1) "1 descarga" else "${stale.size} descargas") +
            " que no escuchas desde hace ${monthsText(months)}. Las de Me gusta no se tocan.",
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
    ) {
        GhostPillButton("Revisar", onClick = onReview)
    }
}

/** Elegir cuáles borrar (todas marcadas al abrir). */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun CleanupSheet(stale: List<StaleDownload>, months: Int, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val selected = remember(stale) { mutableStateListOf<String>().apply { addAll(stale.map { it.id }) } }
    val bytes = stale.filter { it.id in selected }.sumOf { it.totalBytes }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LyraColors.Surface,
    ) {
        Text("Sin escuchar desde hace ${monthsText(months)}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "Se borra el archivo; la canción sigue en tu biblioteca y puedes volver a descargarla.",
            style = MaterialTheme.typography.bodySmall,
            color = LyraColors.TextSecondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                if (selected.isEmpty()) "Nada elegido" else "Borrar ${selected.size} · ${formatBytes(bytes)}",
                onClick = {
                    if (selected.isEmpty()) return@PillButton
                    val ids = selected.toList()
                    val freed = bytes
                    onDismiss()
                    actions.launch {
                        runCatching { ids.forEach { actions.container.downloads.remove(it) } }
                            .onSuccess { actions.message("Liberado ${formatBytes(freed)}") }
                            .onFailure { e ->
                                ErrorLog.record("Descarga", "No se pudieron borrar descargas: ${e.message}", e)
                                actions.message("No se pudieron borrar todas")
                            }
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            GhostPillButton(
                if (selected.size == stale.size) "Ninguna" else "Todas",
                onClick = {
                    if (selected.size == stale.size) selected.clear() else { selected.clear(); selected.addAll(stale.map { it.id }) }
                },
            )
        }
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            items(stale, key = { it.id }) { item ->
                val checked = item.id in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (checked) selected.remove(item.id) else selected.add(item.id) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(item.thumbnailUrl, Modifier.size(46.dp), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            item.artists.joinToString(", ") { it.name } + " · " + formatBytes(item.totalBytes) + " · " +
                                (item.lastPlayedAt?.let { "última vez " + ErrorLog.format(it) } ?: "nunca escuchada"),
                            style = MaterialTheme.typography.bodySmall,
                            color = LyraColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (checked) "Se borrará" else "Se queda",
                        tint = if (checked) LyraColors.Accent else LyraColors.TextTertiary,
                    )
                }
            }
        }
    }
}
