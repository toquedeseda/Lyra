package com.lyra.music.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.core.ErrorEntry
import com.lyra.music.core.ErrorLog
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.ConfirmDialog
import com.lyra.music.ui.components.EmptyView
import com.lyra.music.ui.components.GhostPillButton
import com.lyra.music.ui.components.PageHeader
import com.lyra.music.ui.components.PillButton
import com.lyra.music.ui.components.pressable
import com.lyra.music.ui.theme.LyraColors

/** Ajustes → Informe de errores: lo que ha fallado, para copiarlo o compartirlo. */
@UnstableApi
@Composable
fun ErrorsScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val context = LocalContext.current
    val entries by ErrorLog.entries.collectAsState()
    var expanded by remember { mutableStateOf<ErrorEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Informe de errores", "Lo que ha fallado últimamente. Solo se guarda en tu móvil.") {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteOutline, "Borrar", tint = LyraColors.TextSecondary) }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        "Copiar informe",
                        icon = Icons.Rounded.ContentCopy,
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Informe de Lyra", ErrorLog.report()))
                            actions.message("Informe copiado")
                        },
                    )
                    GhostPillButton(
                        "Compartir",
                        icon = Icons.Rounded.Share,
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Informe de errores de Lyra")
                                .putExtra(Intent.EXTRA_TEXT, ErrorLog.report())
                            context.startActivity(Intent.createChooser(intent, "Compartir informe"))
                        },
                    )
                }
                Text(
                    "Si algo va mal, copia el informe y pásalo: dice exactamente qué falló, en qué versión y en qué móvil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LyraColors.TextSecondary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
            if (entries.isEmpty()) {
                item { EmptyView(Icons.Rounded.CheckCircle, "Todo en orden", "No hay errores guardados.") }
            }
            items(entries, key = { "${it.time}-${it.kind}-${it.message.hashCode()}" }) { entry ->
                ErrorCard(entry, expanded == entry) { expanded = if (expanded == entry) null else entry }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog("¿Borrar el informe?", "Se borran todos los errores guardados.", "Borrar", { confirmClear = false }) {
            ErrorLog.clear()
        }
    }
}

@Composable
private fun ErrorCard(entry: ErrorEntry, expanded: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LyraColors.Surface)
            .border(1.dp, LyraColors.Border, RoundedCornerShape(14.dp))
            .pressable(pressedScale = 0.99f, onClick = onClick)
            .padding(14.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.kind.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.kind == "Cierre") LyraColors.Like else LyraColors.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, LyraColors.Border, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(ErrorLog.format(entry.time), style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary)
        }
        Text(
            entry.message,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val detail = entry.detail
        if (expanded && detail != null) {
            SelectionContainer {
                Text(
                    detail,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = LyraColors.TextSecondary,
                    softWrap = false,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
