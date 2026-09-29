package com.lyra.music.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lyra.music.ui.library.formatBytes
import com.lyra.music.ui.theme.LyraColors
import com.lyra.music.update.UpdateInfo
import com.lyra.music.update.UpdateState

@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    onUpdate: (UpdateInfo) -> Unit,
    onOpenPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !busy, dismissOnBackPress = !busy),
    ) {
        DialogCard(Icons.Rounded.SystemUpdate, title = when (state) {
            is UpdateState.Downloading -> "Descargando"
            is UpdateState.Installing -> "Instalando"
            is UpdateState.Failed -> "No se pudo actualizar"
            is UpdateState.NeedsPermission -> "Falta un permiso"
            else -> "Nueva versión"
        }) {
            when (state) {
                is UpdateState.Available -> {
                    VersionLine(currentVersion, state.info)
                    Notes(state.info.notes)
                    Buttons(
                        secondary = "Más tarde" to onDismiss,
                        primary = "Actualizar" to { onUpdate(state.info) },
                    )
                }
                is UpdateState.Downloading -> {
                    VersionLine(currentVersion, state.info)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = LyraColors.Accent,
                        trackColor = LyraColors.SurfaceHigher,
                    )
                    Text(
                        "${(state.progress * 100).toInt()} %",
                        style = MaterialTheme.typography.bodySmall,
                        color = LyraColors.TextSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                is UpdateState.Installing -> {
                    Text(
                        "Android está instalando Lyra ${state.info.version}. La app se cerrará y podrás volver a abrirla.",
                        color = LyraColors.TextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = LyraColors.Accent, trackColor = LyraColors.SurfaceHigher)
                }
                is UpdateState.NeedsPermission -> {
                    Text(
                        "Para instalar la actualización, Android necesita que permitas a Lyra «instalar apps desconocidas». Solo hay que hacerlo una vez.",
                        color = LyraColors.TextSecondary,
                    )
                    Buttons(
                        secondary = "Cancelar" to onDismiss,
                        primary = "Dar permiso" to onOpenPermission,
                    )
                }
                is UpdateState.Failed -> {
                    Text(state.reason, color = LyraColors.TextSecondary)
                    val info = state.info
                    if (info != null) {
                        Buttons(
                            secondary = "Abrir en GitHub" to {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            },
                            primary = "Reintentar" to { onUpdate(info) },
                        )
                    } else {
                        Buttons(secondary = null, primary = "Cerrar" to onDismiss)
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
fun WhatsNewDialog(version: String, notes: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(Icons.Rounded.NewReleases, "Novedades de la $version") {
            Notes(notes)
            Buttons(secondary = null, primary = "Genial" to onDismiss)
        }
    }
}

@Composable
private fun DialogCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = LyraColors.Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, LyraColors.Border),
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    null,
                    tint = LyraColors.OnAccent,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(LyraColors.Accent)
                        .padding(7.dp),
                )
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 14.dp))
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun VersionLine(current: String, info: UpdateInfo) {
    Text("Lyra $current  →  ${info.version}", style = MaterialTheme.typography.titleMedium)
    if (info.apkSize > 0) {
        Text(formatBytes(info.apkSize), style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
    }
}

@Composable
private fun Notes(notes: String) {
    if (notes.isBlank()) return
    Text(
        cleanMarkdown(notes),
        style = MaterialTheme.typography.bodyMedium,
        color = LyraColors.TextSecondary,
        modifier = Modifier
            .padding(top = 12.dp)
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
    )
}

@Composable
private fun Buttons(secondary: Pair<String, () -> Unit>?, primary: Pair<String, () -> Unit>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secondary != null) {
            TextButton(onClick = secondary.second) { Text(secondary.first, color = LyraColors.TextSecondary) }
        }
        Button(
            onClick = primary.second,
            colors = ButtonDefaults.buttonColors(containerColor = LyraColors.Accent, contentColor = LyraColors.OnAccent),
        ) { Text(primary.first, style = MaterialTheme.typography.labelLarge) }
    }
}

/** Las notas de GitHub vienen en Markdown; se dejan legibles como texto. */
private fun cleanMarkdown(text: String): String = text
    .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
    .replace(Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE), "• ")
    .replace("**", "")
    .replace("`", "")
    .trim()
