package com.lyra.music.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.model.Song
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.GhostPillButton
import com.lyra.music.ui.components.PillButton
import com.lyra.music.ui.theme.LyraColors

/** Vista previa de la tarjeta de una canción, con "Guardar" y "Compartir". */
@UnstableApi
@Composable
fun ShareCardDialog(song: Song, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val context = LocalContext.current
    var card by remember { mutableStateOf<ShareCard.Card?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(song.id) {
        runCatching { ShareCard.create(context, song, actions.coverModel(song)) }
            .onSuccess { card = it }
            .onFailure { failed = true }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(LyraColors.Background.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val width = min(maxWidth - 64.dp, (maxHeight - 170.dp) * (9f / 16f))
            Column(
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .width(width)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(LyraColors.Surface)
                        .border(1.dp, LyraColors.Border, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val current = card
                    when {
                        current != null -> Image(
                            current.bitmap.asImageBitmap(),
                            "Tarjeta de ${song.title}",
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        failed -> Text("No se pudo crear la tarjeta", color = LyraColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        else -> CircularProgressIndicator(color = LyraColors.Accent, strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostPillButton(
                        "Guardar",
                        icon = Icons.Rounded.Download,
                        onClick = {
                            val current = card ?: return@GhostPillButton
                            actions.launch {
                                val saved = ShareCard.saveToGallery(context, current)
                                actions.message(if (saved) "Guardada en Imágenes/Lyra" else "No se pudo guardar")
                            }
                        },
                    )
                    PillButton(
                        "Compartir",
                        icon = Icons.Rounded.Share,
                        onClick = {
                            val current = card ?: return@PillButton
                            actions.shareImage(song, current.file)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}
