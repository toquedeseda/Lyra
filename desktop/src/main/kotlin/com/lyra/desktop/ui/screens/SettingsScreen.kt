package com.lyra.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lyra.desktop.BuildInfo
import com.lyra.desktop.ErrorLog
import com.lyra.desktop.Paths
import com.lyra.desktop.data.AccentColor
import com.lyra.desktop.data.DesktopSettings
import com.lyra.desktop.system.WindowsSystem
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.components.FilterChip
import com.lyra.desktop.ui.components.HoverBox
import com.lyra.desktop.ui.components.OutlinePill
import com.lyra.desktop.ui.components.ThinSlider
import com.lyra.desktop.update.UpdateState
import com.lyra.music.data.source.soundcloud.AudioQuality
import com.lyra.music.playback.EqPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser

@Composable
fun SettingsScreen() {
    val actions = LocalActions.current
    val app = actions.app
    val s by app.settings.flow.collectAsState()
    val update: (DesktopSettings.() -> DesktopSettings) -> Unit = { transform -> app.settings.update { it.transform() } }
    val scope = rememberCoroutineScope()
    ScreenList {
        item {
            Text("Ajustes", style = MaterialTheme.typography.displayMedium, color = LyraColors.TextPrimary, modifier = Modifier.padding(start = PagePadding, top = 8.dp, bottom = 8.dp))
        }
        item {
            Group("Sonido") {
                Choice("Calidad al escuchar", "Alta gasta más datos; en casa con wifi, mejor Alta.", qualityLabels, s.streamQuality.ordinal) {
                    update { copy(streamQuality = AudioQuality.entries[it]) }
                }
                Toggle(
                    "Fundido entre canciones",
                    "Como en Spotify: el final de cada canción se funde con el principio de la siguiente.",
                    s.crossfadeSeconds > 0,
                ) { on ->
                    update {
                        if (on) copy(crossfadeSeconds = crossfadeLast.coerceIn(1, 12))
                        else copy(crossfadeLast = crossfadeSeconds.takeIf { it > 0 } ?: crossfadeLast, crossfadeSeconds = 0)
                    }
                }
                if (s.crossfadeSeconds > 0) {
                    SettingRow("Duración del fundido", "Cuántos segundos se mezclan.") {
                        Text("1 s", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary)
                        Spacer(Modifier.width(10.dp))
                        ThinSlider(
                            (s.crossfadeSeconds - 1) / 11f,
                            { v -> update { copy(crossfadeSeconds = 1 + kotlin.math.round(v * 11).toInt()) } },
                            Modifier.width(200.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("12 s", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary)
                        Spacer(Modifier.width(14.dp))
                        Text("${s.crossfadeSeconds} s", style = MaterialTheme.typography.titleSmall, color = LyraColors.TextPrimary, modifier = Modifier.width(40.dp))
                    }
                }
                Toggle("Igualar el volumen", "Que todas las canciones suenen igual de fuerte.", s.normalizeVolume) { update { copy(normalizeVolume = it) } }
                Toggle("Radio infinita", "Cuando se acaba la cola, sigue con canciones parecidas.", s.infiniteRadio) { update { copy(infiniteRadio = it) } }
                Toggle("Radio sin repetir", "La radio evita lo que has escuchado en las últimas 48 horas.", s.radioNoRepeat) { update { copy(radioNoRepeat = it) } }
            }
        }
        item { EqualizerGroup(s, update) }
        item {
            Group("Aspecto") {
                SettingRow("Color de Lyra", "Botones, barras y detalles. El corazón de «Me gusta» sigue siendo rojo.") {}
                AccentPicker(s.accent) { color -> update { copy(accent = color) } }
            }
        }
        item {
            Group("Descargas") {
                Choice("Calidad de las descargas", "En M4A, con la carátula dentro.", qualityLabels, s.downloadQuality.ordinal) {
                    update { copy(downloadQuality = AudioQuality.entries[it]) }
                }
                SettingRow("Carpeta", Paths.downloadsFolder(s.downloadsFolder).absolutePath) {
                    OutlinePill("Cambiar…", onClick = {
                        val chooser = JFileChooser(Paths.downloadsFolder(s.downloadsFolder)).apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "¿Dónde guardo las descargas?"
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) update { copy(downloadsFolder = chooser.selectedFile.absolutePath) }
                    })
                    Spacer(Modifier.width(8.dp))
                    OutlinePill("Abrir", onClick = { actions.openFolder(Paths.downloadsFolder(s.downloadsFolder)) })
                }
            }
        }
        item {
            Group("Ventana") {
                Toggle("Al cerrar, seguir sonando", "La X esconde la ventana y Lyra sigue en la bandeja de Windows (junto al reloj).", s.closeToTray) { update { copy(closeToTray = it) } }
                if (WindowsSystem.canStartWithWindows) {
                    Toggle("Abrir Lyra al encender el PC", "Se abre escondida en la bandeja, lista para sonar.", s.startWithWindows) { enabled ->
                        update { copy(startWithWindows = enabled) }
                        WindowsSystem.setStartWithWindows(enabled)
                    }
                }
                if (com.lyra.desktop.system.DiscordPresence.available) {
                    Toggle("Mostrar en Discord lo que escuchas", "En tu perfil sale «Escuchando Lyra» con la canción (si Discord está abierto en el PC).", s.discordPresence) { update { copy(discordPresence = it) } }
                }
            }
        }
        item { SyncGroup() }
        item {
            Group("Almacenamiento") {
                var used by remember { mutableLongStateOf(-1L) }
                LaunchedEffect(Unit) { used = withContext(Dispatchers.IO) { app.audioCache.sizeBytes() } }
                Choice(
                    "Caché de lo escuchado",
                    "Lo que escuchas se guarda para que vuelva a sonar al instante y sin red." + if (used >= 0) " Ahora ocupa ${formatSize(used)}." else "",
                    listOf("512 MB", "1 GB", "2 GB", "5 GB"),
                    listOf(512, 1024, 2048, 5120).indexOf(s.cacheLimitMb).coerceAtLeast(0),
                ) { index ->
                    update { copy(cacheLimitMb = listOf(512, 1024, 2048, 5120)[index]) }
                    scope.launch(Dispatchers.IO) { app.audioCache.trim() }
                }
                SettingRow("Vaciar la caché", "No borra tus descargas ni tu biblioteca.") {
                    OutlinePill("Vaciar", onClick = {
                        scope.launch(Dispatchers.IO) {
                            app.audioCache.clear()
                            used = app.audioCache.sizeBytes()
                            actions.message("Caché vaciada")
                        }
                    })
                }
            }
        }
        item {
            Group("Lyra") {
                val updateState by app.updater.state.collectAsState()
                SettingRow(
                    "Versión ${BuildInfo.VERSION}",
                    when (val u = updateState) {
                        is UpdateState.Available -> "Hay una versión nueva: ${u.version}"
                        is UpdateState.Downloading -> "Bajando la ${u.version}… ${(u.progress * 100).toInt()} %"
                        is UpdateState.Ready -> "La ${u.version} está lista: reinicia Lyra para ponerla."
                        is UpdateState.Failed -> u.message
                        UpdateState.None -> if (app.updater.supported) "Se actualiza sola desde GitHub." else "Versión de pruebas (sin actualizaciones)."
                    },
                ) {
                    if (updateState is UpdateState.Ready) {
                        OutlinePill("Reiniciar y actualizar", onClick = { app.updater.install(beforeExit = { app.shutdown() }) })
                    } else if (app.updater.supported) {
                        OutlinePill("Buscar ahora", onClick = {
                            scope.launch {
                                runCatching { app.updater.check() }
                                    .onSuccess { found -> if (found == null) actions.message("Tienes la última versión") }
                                    .onFailure { actions.message("No se pudo mirar si hay versión nueva. Comprueba la conexión.") }
                            }
                        })
                    }
                }
                Toggle("Actualizar sola", "Busca versiones nuevas al abrir y cada media hora.", s.checkUpdates) { update { copy(checkUpdates = it) } }
                val errors by ErrorLog.entries.collectAsState()
                SettingRow("Informe de errores", if (errors.isEmpty()) "No hay errores apuntados." else (if (errors.size == 1) "1 apuntado" else "${errors.size} apuntados") + " (cierres, canciones que no sonaron…).") {
                    if (errors.isNotEmpty()) {
                        OutlinePill("Copiar", onClick = {
                            actions.copy(ErrorLog.report())
                            actions.message("Informe copiado: pégalo en un mensaje")
                        })
                        Spacer(Modifier.width(8.dp))
                        OutlinePill("Borrar", onClick = { ErrorLog.clear() })
                    }
                }
                SettingRow("Tus datos", Paths.data.absolutePath) {
                    OutlinePill("Abrir carpeta", onClick = { actions.openFolder(Paths.data) })
                }
            }
        }
    }
}

private val qualityLabels = listOf("Alta", "Normal", "Baja")

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = PagePadding, vertical = 14.dp).widthIn(max = 860.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = LyraColors.TextPrimary, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 20.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = LyraColors.TextPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    HoverBox(onClick = { onChange(!checked) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SettingRow(title, subtitle) {} }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LyraColors.OnAccent,
                    checkedTrackColor = LyraColors.Accent,
                    uncheckedThumbColor = LyraColors.TextSecondary,
                    uncheckedTrackColor = LyraColors.SurfaceHigher,
                    uncheckedBorderColor = LyraColors.SurfaceHigher,
                ),
            )
        }
    }
}

@Composable
private fun Choice(title: String, subtitle: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SettingRow(title, subtitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, label -> FilterChip(label, index == selected, onClick = { onSelect(index) }) }
        }
    }
}

@Composable
private fun SliderSetting(title: String, subtitle: String, value: Float, onChange: (Float) -> Unit) {
    SettingRow(title, subtitle) {
        ThinSlider(value, onChange, Modifier.width(220.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(current: AccentColor, onPick: (AccentColor) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        AccentColor.entries.forEach { color ->
            val selected = color == current
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
                HoverBox(onClick = { onPick(color) }, shape = CircleShape) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .then(if (selected) Modifier.border(2.dp, LyraColors.TextPrimary, CircleShape) else Modifier)
                            .padding(if (selected) 4.dp else 0.dp)
                            .clip(CircleShape)
                            .background(Color(color.argb)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, null, tint = LyraColors.OnAccent, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(color.label, style = MaterialTheme.typography.bodySmall, color = if (selected) LyraColors.TextPrimary else LyraColors.TextSecondary, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------- ecualizador

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerGroup(s: DesktopSettings, update: (DesktopSettings.() -> DesktopSettings) -> Unit) {
    Group("Ecualizador") {
        Toggle("Ecualizador", "Sube o baja graves, medios y agudos.", s.eqEnabled) { update { copy(eqEnabled = it) } }
        if (s.eqEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                EqPresets.all.forEach { preset ->
                    FilterChip(preset.label, s.eqPreset == preset.key, onClick = { update { copy(eqPreset = preset.key, eqBands = preset.bands) } })
                }
                if (EqPresets.byKey(s.eqPreset) == null) FilterChip("Personalizado", true, onClick = {})
            }
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
                s.eqBands.forEachIndexed { index, db ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${if (db > 0) "+" else ""}${db.toInt()}", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        BandSlider(db) { value ->
                            update { copy(eqBands = eqBands.toMutableList().also { it[index] = value }, eqPreset = "custom") }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(labels[index], style = MaterialTheme.typography.labelMedium, color = LyraColors.TextTertiary)
                    }
                }
            }
            SliderSetting("Volumen del ecualizador", "${if (s.eqPreamp > 0) "+" else ""}${"%.1f".format(s.eqPreamp)} dB", (s.eqPreamp + 12f) / 24f) {
                update { copy(eqPreamp = (it * 24f - 12f)) }
            }
        }
    }
}

/** Barra vertical de una banda (−12 a +12 dB). Doble clic: a 0. */
@Composable
private fun BandSlider(db: Float, onChange: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    androidx.compose.foundation.Canvas(
        Modifier
            .width(22.dp)
            .height(150.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onChange(0f) }) { offset ->
                    onChange(((1 - offset.y / size.height) * 24f - 12f).coerceIn(-12f, 12f).let { kotlin.math.round(it) })
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, _ ->
                    onChange(((1 - change.position.y / size.height) * 24f - 12f).coerceIn(-12f, 12f).let { kotlin.math.round(it * 2) / 2 })
                }
            },
    ) {
        val track = 4.dp.toPx()
        val x = (size.width - track) / 2
        drawRoundRect(LyraColors.SurfaceHigher, Offset(x, 0f), Size(track, size.height), CornerRadius(track / 2))
        val center = size.height / 2
        val y = size.height * (1 - (db + 12f) / 24f)
        drawRoundRect(LyraColors.Accent, Offset(x, minOf(center, y)), Size(track, kotlin.math.abs(center - y)), CornerRadius(track / 2))
        drawCircle(if (dragging) LyraColors.Accent else LyraColors.TextPrimary, 7.dp.toPx(), Offset(size.width / 2, y))
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
    else -> "${bytes / 1024} KB"
}
