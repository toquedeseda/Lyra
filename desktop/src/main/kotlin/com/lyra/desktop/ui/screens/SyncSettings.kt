package com.lyra.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyra.desktop.sync.SyncStatus
import com.lyra.desktop.ui.ConfirmRequest
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.components.FilledPill
import com.lyra.desktop.ui.components.LyraTextField
import com.lyra.desktop.ui.components.OutlinePill
import com.lyra.music.sync.SyncCode
import com.lyra.music.sync.formatPairCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ajustes → Biblioteca sincronizada: emparejar con el móvil, como en Spotify (sin cuenta). */
@Composable
fun SyncGroup() {
    val actions = LocalActions.current
    val sync = actions.app.sync
    val status by sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var shownCode by remember { mutableStateOf<SyncCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = PagePadding, vertical = 14.dp).widthIn(max = 860.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(Icons.Rounded.Sync, null, tint = LyraColors.TextPrimary)
            Spacer(Modifier.width(10.dp))
            Text("Biblioteca sincronizada", style = MaterialTheme.typography.headlineSmall, color = LyraColors.TextPrimary)
        }
        Text(
            "Tus Me gusta, playlists, carpetas, álbumes y artistas, iguales en el móvil y en el PC: lo que cambies en uno sale en el otro.",
            style = MaterialTheme.typography.bodySmall,
            color = LyraColors.TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        when (val s = status) {
            is SyncStatus.Off -> {
                s.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LyraColors.Like, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text("Si ya la tienes en el móvil: Ajustes → Sincronizar con el PC → Activar, y escribe aquí el código que te da.", style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextPrimary)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LyraTextField(code, { code = it.uppercase().take(7) }, "Código (p. ej. K7P-2MX)", Modifier.width(240.dp), onSubmit = {})
                    Spacer(Modifier.width(10.dp))
                    FilledPill(if (busy) "Emparejando…" else "Emparejar", enabled = !busy && code.count { it.isLetterOrDigit() } == 6, onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { sync.join(code) }
                                .onSuccess {
                                    code = ""
                                    actions.message("¡Listo! Tu biblioteca ya está sincronizada")
                                }
                                .onFailure { error = it.message ?: "No se pudo emparejar" }
                            busy = false
                        }
                    })
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("¿Empiezas por el PC?", style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
                    Spacer(Modifier.width(10.dp))
                    OutlinePill("Activar aquí y ver el código", onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { sync.createLibrary() }
                                .onSuccess { shownCode = it }
                                .onFailure { error = it.message ?: "No se pudo activar" }
                            busy = false
                        }
                    })
                }
            }
            else -> {
                val now by produceState(System.currentTimeMillis()) {
                    while (true) {
                        delay(15_000)
                        value = System.currentTimeMillis()
                    }
                }
                Text(
                    when (s) {
                        is SyncStatus.Syncing -> "Sincronizando…"
                        is SyncStatus.Done -> "Sincronizada · " + ago(now, s.at)
                        is SyncStatus.Failed -> "No se pudo sincronizar (${s.message}). Se reintenta sola." + if (s.lastAt > 0) " Última vez: " + ago(now, s.lastAt) else ""
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (s is SyncStatus.Failed) LyraColors.Like else LyraColors.TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinePill("Sincronizar ahora", onClick = { scope.launch { sync.syncNow() } })
                    Spacer(Modifier.width(8.dp))
                    OutlinePill("Emparejar otro dispositivo", onClick = {
                        scope.launch {
                            runCatching { sync.newCode() }.onSuccess { shownCode = it }.onFailure { error = it.message }
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                    OutlinePill("Dejar de sincronizar", onClick = {
                        actions.confirmRequest = ConfirmRequest(
                            "¿Dejar de sincronizar?",
                            "Este PC se queda con la biblioteca tal como está ahora, pero ya no recibirá ni mandará cambios.",
                            "Dejar de sincronizar",
                        ) { scope.launch { sync.leave() } }
                    })
                }
            }
        }
        shownCode?.let { pairing ->
            val left by produceState(pairing.caduca - System.currentTimeMillis(), pairing) {
                while (value > 0) {
                    delay(1_000)
                    value = pairing.caduca - System.currentTimeMillis()
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.background(LyraColors.SurfaceHigh, RoundedCornerShape(12.dp)).padding(18.dp)) {
                Text("Escribe este código en el otro dispositivo:", style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Text(
                    formatPairCode(pairing.codigo),
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 40.sp, letterSpacing = 4.sp),
                    color = if (left > 0) LyraColors.TextPrimary else LyraColors.TextTertiary,
                )
                Text(
                    if (left > 0) "Vale ${(left / 60_000) + 1} min más. En el móvil: Ajustes → Sincronizar con el PC → Tengo un código." else "Ha caducado: pide otro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LyraColors.TextSecondary,
                )
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = LyraColors.Like)
        }
    }
}

private fun ago(now: Long, at: Long): String {
    val minutes = (now - at) / 60_000
    return when {
        at <= 0 -> "nunca"
        minutes < 1 -> "ahora mismo"
        minutes == 1L -> "hace 1 minuto"
        minutes < 60 -> "hace $minutes minutos"
        minutes < 120 -> "hace 1 hora"
        minutes < 1440 -> "hace ${minutes / 60} horas"
        else -> "hace ${minutes / 1440} días"
    }
}
