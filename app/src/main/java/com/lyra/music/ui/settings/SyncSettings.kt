package com.lyra.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyra.music.sync.SyncCode
import com.lyra.music.sync.SyncStatus
import com.lyra.music.sync.formatPairCode
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.theme.LyraColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ajustes → Sincronizar con el PC: la fila con el estado; al tocarla, el cuadro para emparejar. */
@Composable
fun SyncRow() {
    val actions = LocalActions.current
    val sync = actions.container.sync
    val status by sync.status.collectAsState()
    var open by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("Biblioteca sincronizada", style = MaterialTheme.typography.bodyLarge)
        Text(
            when (val s = status) {
                is SyncStatus.Off -> s.message ?: "Tus Me gusta, playlists y carpetas, iguales en el móvil y en Lyra para Windows"
                SyncStatus.Syncing -> "Sincronizando…"
                is SyncStatus.Done -> "Activada · " + ago(now, s.at)
                is SyncStatus.Failed -> "No se pudo sincronizar (${s.message}); se reintenta sola"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is SyncStatus.Failed) LyraColors.Like else LyraColors.TextSecondary,
        )
    }
    if (open) SyncDialog(onDismiss = { open = false })
}

@Composable
private fun SyncDialog(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val sync = actions.container.sync
    val status by sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<SyncCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val paired = status !is SyncStatus.Off

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = LyraColors.Surface,
        title = { Text("Sincronizar con el PC", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                Text(
                    "Tus Me gusta, playlists, carpetas, álbumes y artistas, iguales en el móvil y en Lyra para Windows: lo que cambies en uno sale en el otro.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyraColors.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                if (!paired) {
                    TextButton(onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { sync.createLibrary() }.onSuccess { shown = it }.onFailure { error = it.message ?: "No se pudo activar" }
                            busy = false
                        }
                    }, enabled = !busy) {
                        Text(if (busy) "Activando…" else "Activar y ver el código para el PC", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "Antes se guarda una copia de tu biblioteca en el móvil, por si acaso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LyraColors.TextTertiary,
                    )
                    HorizontalDivider(color = LyraColors.Border, modifier = Modifier.padding(vertical = 14.dp))
                    Text("¿Ya la activaste en el PC? Escribe el código que te da:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().take(7) },
                        singleLine = true,
                        placeholder = { Text("K7P-2MX") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LyraColors.Accent,
                            unfocusedBorderColor = LyraColors.Border,
                            cursorColor = LyraColors.Accent,
                            focusedTextColor = LyraColors.TextPrimary,
                        ),
                    )
                    TextButton(onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { sync.join(code) }
                                .onSuccess {
                                    actions.message("¡Listo! Tu biblioteca ya está sincronizada")
                                    onDismiss()
                                }
                                .onFailure { error = it.message ?: "No se pudo emparejar" }
                            busy = false
                        }
                    }, enabled = !busy && code.count { it.isLetterOrDigit() } == 6) {
                        Text("Emparejar", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    TextButton(onClick = { scope.launch { sync.syncNow() } }) {
                        Text("Sincronizar ahora", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = {
                        scope.launch { runCatching { sync.newCode() }.onSuccess { shown = it }.onFailure { error = it.message } }
                    }) {
                        Text("Emparejar otro dispositivo", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge)
                    }
                    if (confirmLeave) {
                        Text(
                            "El móvil se queda con la biblioteca como está, pero ya no recibirá ni mandará cambios.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LyraColors.TextSecondary,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                sync.leave()
                                onDismiss()
                            }
                        }) { Text("Sí, dejar de sincronizar", color = LyraColors.Like, style = MaterialTheme.typography.labelLarge) }
                    } else {
                        TextButton(onClick = { confirmLeave = true }) {
                            Text("Dejar de sincronizar", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                shown?.let { pairing ->
                    val left by produceState(pairing.caduca - System.currentTimeMillis(), pairing) {
                        while (value > 0) {
                            delay(1_000)
                            value = pairing.caduca - System.currentTimeMillis()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(LyraColors.SurfaceHigh, RoundedCornerShape(12.dp))
                            .padding(16.dp),
                    ) {
                        Text("Escribe este código en Lyra para Windows (Ajustes → Biblioteca sincronizada):", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            formatPairCode(pairing.codigo),
                            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 34.sp, letterSpacing = 3.sp),
                            color = if (left > 0) LyraColors.TextPrimary else LyraColors.TextTertiary,
                        )
                        Text(
                            if (left > 0) "Vale ${(left / 60_000) + 1} min más." else "Ha caducado: pide otro.",
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cerrar", color = LyraColors.TextSecondary) }
        },
    )
}

private fun ago(now: Long, at: Long): String {
    val minutes = (now - at) / 60_000
    return when {
        at <= 0 -> "aún sin sincronizar"
        minutes < 1 -> "sincronizada ahora mismo"
        minutes == 1L -> "hace 1 minuto"
        minutes < 60 -> "hace $minutes minutos"
        minutes < 120 -> "hace 1 hora"
        minutes < 1440 -> "hace ${minutes / 60} horas"
        else -> "hace ${minutes / 1440} días"
    }
}
