package com.lyra.music.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.lyra.music.BuildConfig
import com.lyra.music.data.settings.AppSettings
import com.lyra.music.data.source.soundcloud.AudioQuality
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.PageHeader
import com.lyra.music.ui.components.ConfirmDialog
import com.lyra.music.ui.home.ignoresBatteryOptimizations
import com.lyra.music.ui.home.openBatterySettings
import com.lyra.music.ui.library.formatBytes
import com.lyra.music.ui.library.monthsText
import com.lyra.music.ui.navigation.EqualizerRoute
import com.lyra.music.ui.navigation.IslandRoute
import com.lyra.music.ui.theme.LyraColors
import com.lyra.music.widget.LyraWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.lyra.music.data.settings.ScreenTransition

@UnstableApi
@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val container = actions.container
    val context = LocalContext.current
    val s by container.settings.flow.collectAsState()
    val downloadBytes by container.downloads.totalBytes.collectAsState(initial = 0L)
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var cacheVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(cacheVersion) { cacheBytes = withContext(Dispatchers.IO) { container.playerCache.cacheSpace } }

    var qualityDialog by remember { mutableStateOf<String?>(null) }
    var regionDialog by remember { mutableStateOf(false) }
    var transitionDialog by remember { mutableStateOf(false) }
    var confirmDownloads by remember { mutableStateOf(false) }
    var confirmHistory by remember { mutableStateOf(false) }
    var includeDownloads by remember { mutableStateOf(false) }
    var staleDialog by remember { mutableStateOf(false) }
    val errors by com.lyra.music.core.ErrorLog.entries.collectAsState()

    fun update(transform: (AppSettings) -> AppSettings) = actions.launch { container.settings.update(transform) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) actions.launch {
            runCatching { container.backup.export(uri, includeDownloads) }
                .onSuccess { actions.message("Copia guardada: ${it.songs} canciones, ${it.playlists} playlists" + if (includeDownloads) ", ${it.downloads} descargas" else "") }
                .onFailure { actions.message("No se pudo exportar: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actions.launch {
            runCatching { container.backup.import(uri) }
                .onSuccess { actions.message("Restaurado: ${it.songs} canciones, ${it.playlists} playlists, ${it.downloads} descargas") }
                .onFailure { actions.message("No se pudo importar: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Ajustes", "Reproducción, descargas, isla y copias de seguridad.")
        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { Group("Reproducción") }
            item { NavRow("Calidad de streaming", qualityLabel(s.streamQuality)) { qualityDialog = "stream" } }
            item {
                SliderRow(
                    title = "Crossfade",
                    valueLabel = if (s.crossfadeSeconds == 0) "Desactivado" else "${s.crossfadeSeconds} s",
                    value = s.crossfadeSeconds.toFloat(),
                    range = 0f..12f,
                    steps = 11,
                    description = "La siguiente canción entra mientras termina la anterior.",
                ) { value -> update { it.copy(crossfadeSeconds = value.toInt()) } }
            }
            if (s.crossfadeSeconds > 0) {
                item {
                    SwitchRow(
                        "Crossfade inteligente",
                        "No mezcla pistas seguidas de un mismo álbum, y la siguiente entra cuando la canción acaba de verdad (en su fundido o silencio final).",
                        s.smartCrossfade,
                    ) { v -> update { it.copy(smartCrossfade = v) } }
                }
            }
            item { SwitchRow("Igualar volumen", "Todas las canciones suenan a un volumen parecido.", s.normalizeVolume) { v -> update { it.copy(normalizeVolume = v) } } }
            item { SwitchRow("Saltar silencios", "Recorta los silencios largos al principio y al final.", s.skipSilence) { v -> update { it.copy(skipSilence = v) } } }
            item { SwitchRow("Radio infinita", "Al acabar la cola sigue con canciones parecidas.", s.infiniteRadio) { v -> update { it.copy(infiniteRadio = v) } } }
            item {
                SwitchRow(
                    "Radio sin repetir",
                    "Evita lo que has escuchado en las últimas 48 horas y no pone dos seguidas del mismo artista.",
                    s.radioNoRepeat,
                ) { v -> update { it.copy(radioNoRepeat = v) } }
            }
            item { HeadsetRow(s.resumeOnConnect) { v -> update { it.copy(resumeOnConnect = v) } } }
            item { NavRow("Ecualizador", if (s.eqEnabled) "Activado" else "Desactivado") { actions.nav.navigate(EqualizerRoute) } }
            item { SwitchRow("Mostrar letras", "Letras sincronizadas de LRCLIB.", s.showLyrics) { v -> update { it.copy(showLyrics = v) } } }

            item { Group("Descargas") }
            item { NavRow("Calidad de descarga", qualityLabel(s.downloadQuality)) { qualityDialog = "download" } }
            item {
                SwitchRow(
                    "Guardar en Música/Lyra",
                    if (s.downloadsVisible) "Visibles en el gestor de archivos, otras apps y el PC: «Artista - Canción.m4a» con carátula."
                    else "Ocultas dentro de Lyra (Opus, como en Spotify).",
                    s.downloadsVisible,
                ) { v ->
                    update { it.copy(downloadsVisible = v) }
                    if (v) actions.launch {
                        val moved = container.downloads.migrateHiddenToFolder()
                        if (moved > 0) actions.message("Pasando $moved descargas a Música/Lyra…")
                    }
                }
            }
            item { SwitchRow("Solo con Wi‑Fi", "No gasta datos móviles al descargar.", s.downloadWifiOnly) { v -> update { it.copy(downloadWifiOnly = v) } } }
            item { SwitchRow("Descargar las que te gustan", "Al darle al corazón, se descarga sola.", s.autoDownloadLiked) { v -> update { it.copy(autoDownloadLiked = v) } } }
            item {
                NavRow(
                    "Avisar de descargas sin escuchar",
                    if (s.staleDownloadMonths == 0) "Nunca" else "Si llevas ${monthsText(s.staleDownloadMonths)} sin escucharlas (las de Me gusta no cuentan)",
                ) { staleDialog = true }
            }
            item { InfoRow("Espacio usado", "Descargas: ${formatBytes(downloadBytes)} · Caché: ${formatBytes(cacheBytes)}") }
            item {
                NavRow("Vaciar la caché", "Lo escuchado recientemente sin descargar (${formatBytes(cacheBytes)})") {
                    actions.launch {
                        withContext(Dispatchers.IO) { container.clearPlayerCache() }
                        cacheVersion++
                        actions.message("Caché vaciada")
                    }
                }
            }
            item { NavRow("Borrar todas las descargas", formatBytes(downloadBytes)) { confirmDownloads = true } }

            item { Group("Aspecto") }
            item { NavRow("Animación entre pantallas", transitionLabel(s.screenTransition)) { transitionDialog = true } }
            item {
                SwitchRow(
                    "Barritas de música",
                    "En el reproductor, unas barritas bajo la portada se mueven con la música y la portada sigue el bajo.",
                    s.visualizer,
                ) { v -> update { it.copy(visualizer = v) } }
            }

            item { Group("Isla, widget y pantalla de bloqueo") }
            item { NavRow("Isla flotante", if (s.islandEnabled) "Activada" else "Desactivada") { actions.nav.navigate(IslandRoute) } }
            item {
                NavRow("Widget", "Lo que suena en la pantalla de inicio, con carátula y controles") {
                    actions.launch {
                        val manager = GlanceAppWidgetManager(context)
                        val pinned = runCatching { manager.requestPinGlanceAppWidget(LyraWidgetReceiver::class.java) }.getOrDefault(false)
                        if (!pinned) actions.message("Mantén pulsada la pantalla de inicio → Widgets → Lyra")
                    }
                }
            }
            item { InfoRow("Pantalla de bloqueo", "Los controles salen solos en la pantalla de bloqueo y en la notificación mientras suena algo.") }

            item { Group("Segundo plano (Vivo)") }
            item {
                val ignoring = ignoresBatteryOptimizations(context)
                NavRow(
                    "Optimización de batería",
                    if (ignoring) "Desactivada para Lyra ✓" else "Activa: la música puede cortarse con la pantalla apagada",
                ) { openBatterySettings(context) }
            }
            item {
                NavRow("Inicio automático y consumo en segundo plano", "Abre los ajustes de Lyra y permite «Consumo alto en segundo plano».") {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            }

            item { Group("Copia de seguridad") }
            item { SwitchRow("Incluir canciones descargadas", "El archivo ocupará bastante más.", includeDownloads) { includeDownloads = it } }
            item {
                NavRow("Exportar copia", "Biblioteca, playlists, historial y ajustes en un .zip") {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("lyra-copia-$date.zip")
                }
            }
            item { NavRow("Importar copia", "Se fusiona con lo que ya tienes") { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) } }

            item { Group("Contenido") }
            item { NavRow("País del contenido", regionName(s.region)) { regionDialog = true } }
            item { NavRow("Borrar historial", "Historial de escucha y estadísticas de tus mixes") { confirmHistory = true } }
            item {
                NavRow("Borrar búsquedas recientes", "") {
                    actions.launch { container.library.clearSearches() }
                    actions.message("Búsquedas borradas")
                }
            }

            item { Group("Actualizaciones") }
            item { InfoRow("Versión instalada", "Lyra ${BuildConfig.VERSION_NAME}") }
            item { NavRow("Buscar actualizaciones", "En github.com/${BuildConfig.UPDATE_REPO}") { container.updates.check(force = true) } }
            item {
                SwitchRow(
                    "Buscar actualizaciones solo",
                    "Al abrir la app y cada 6 horas. Si hay versión nueva, te avisa con una notificación.",
                    s.checkUpdates,
                ) { v -> update { it.copy(checkUpdates = v) } }
            }
            item {
                NavRow("Notificaciones", "Actualizaciones, lanzamientos de tus artistas y playlists sincronizadas") {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }

            item { Group("Ayuda") }
            item {
                NavRow(
                    "Informe de errores",
                    if (errors.isEmpty()) "Sin errores guardados" else "${errors.size} guardados · cópialo y pásalo si algo va mal",
                ) { actions.nav.navigate(com.lyra.music.ui.navigation.ErrorsRoute) }
            }

            item { Group("Acerca de") }
            item {
                InfoRow(
                    "Lyra 2.0",
                    "App personal. Música de YouTube Music y SoundCloud mediante NewPipeExtractor (GPLv3). Letras de LRCLIB. Solo para uso propio.",
                )
            }
        }
    }

    qualityDialog?.let { which ->
        val current = if (which == "stream") s.streamQuality else s.downloadQuality
        ChoiceDialog(
            title = if (which == "stream") "Calidad de streaming" else "Calidad de descarga",
            options = AudioQuality.entries.map { qualityLabel(it) to qualityDescription(it) },
            selected = AudioQuality.entries.indexOf(current),
            onDismiss = { qualityDialog = null },
        ) { index ->
            val quality = AudioQuality.entries[index]
            update { if (which == "stream") it.copy(streamQuality = quality) else it.copy(downloadQuality = quality) }
            qualityDialog = null
        }
    }
    if (staleDialog) {
        val options = listOf(0, 3, 6, 12)
        ChoiceDialog(
            title = "Avisar de descargas sin escuchar",
            options = options.map { (if (it == 0) "Nunca" else "Tras ${monthsText(it)}") to "" },
            selected = options.indexOf(s.staleDownloadMonths).coerceAtLeast(0),
            onDismiss = { staleDialog = false },
        ) { index ->
            update { it.copy(staleDownloadMonths = options[index]) }
            staleDialog = false
        }
    }
    if (transitionDialog) {
        val options = ScreenTransition.entries
        ChoiceDialog(
            title = "Animación entre pantallas",
            options = options.map { transitionLabel(it) to transitionDescription(it) },
            selected = options.indexOf(s.screenTransition),
            onDismiss = { transitionDialog = false },
        ) { index ->
            update { it.copy(screenTransition = options[index]) }
            transitionDialog = false
        }
    }
    if (regionDialog) {
        ChoiceDialog(
            title = "País del contenido",
            options = REGIONS.map { it.second to "" },
            selected = REGIONS.indexOfFirst { it.first == s.region }.coerceAtLeast(0),
            onDismiss = { regionDialog = false },
        ) { index ->
            update { it.copy(region = REGIONS[index].first) }
            actions.launch { container.home.refresh(force = true) }
            regionDialog = false
        }
    }
    if (confirmDownloads) {
        ConfirmDialog("¿Borrar todas las descargas?", "Se liberarán ${formatBytes(downloadBytes)}.", "Borrar", { confirmDownloads = false }) {
            actions.launch { container.downloads.clearAll() }
        }
    }
    if (confirmHistory) {
        ConfirmDialog("¿Borrar el historial?", "Tus mixes empezarán de cero.", "Borrar", { confirmHistory = false }) {
            actions.launch { container.library.clearHistory() }
        }
    }
}

private val REGIONS = listOf(
    "ES" to "España", "MX" to "México", "AR" to "Argentina", "CO" to "Colombia", "CL" to "Chile",
    "PE" to "Perú", "US" to "Estados Unidos", "GB" to "Reino Unido", "PR" to "Puerto Rico",
)

private fun transitionLabel(style: ScreenTransition) = when (style) {
    ScreenTransition.SMOOTH -> "Suave (recomendada)"
    ScreenTransition.COVER -> "Portada que vuela"
    ScreenTransition.NONE -> "Sin animación"
}

private fun transitionDescription(style: ScreenTransition) = when (style) {
    ScreenTransition.SMOOTH -> "Como siempre, pero más fluida: la pantalla nueva entra deslizándose un poco."
    ScreenTransition.COVER -> "Al abrir un álbum o una playlist, su portada viaja hasta arriba y crece."
    ScreenTransition.NONE -> "Cambia de pantalla al instante."
}

private fun regionName(code: String) = REGIONS.firstOrNull { it.first == code }?.second ?: code

fun qualityLabel(quality: AudioQuality) = when (quality) {
    AudioQuality.HIGH -> "Alta"
    AudioQuality.NORMAL -> "Normal"
    AudioQuality.LOW -> "Ahorro de datos"
}

private fun qualityDescription(quality: AudioQuality) = when (quality) {
    AudioQuality.HIGH -> "La mejor disponible (~160 kbps Opus)"
    AudioQuality.NORMAL -> "Alrededor de 128 kbps"
    AudioQuality.LOW -> "~50 kbps, gasta muy pocos datos"
}

/** "Seguir al conectar auriculares" y, si falta, el permiso para que funcione con Lyra cerrada. */
@Composable
private fun HeadsetRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    fun granted() = !needsPermission ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        denied = !it
    }
    Column {
        SwitchRow(
            "Seguir al conectar auriculares",
            "Si la música se paró al quitar los cascos (o al salir del coche), sigue sola al volver a conectarlos.",
            enabled,
        ) { v ->
            onChange(v)
            if (v && !hasPermission) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (enabled && !hasPermission) {
            NavRow(
                "Permitir «Dispositivos cercanos»",
                if (denied) "Ábrelo en Permisos de Lyra para que funcione con la app cerrada."
                else "Para que funcione también con Lyra cerrada (cascos Bluetooth).",
            ) {
                if (denied) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                } else {
                    launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        }
    }
}

@Composable
fun Group(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 6.dp),
    )
}

@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
    }
}

@Composable
fun InfoRow(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
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

@Composable
fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    description: String? = null,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
        }
        if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = LyraColors.Accent,
                activeTrackColor = LyraColors.Accent,
                inactiveTrackColor = LyraColors.SurfaceHigher,
                activeTickColor = LyraColors.OnAccent,
                inactiveTickColor = LyraColors.TextTertiary,
            ),
        )
    }
}

@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyraColors.Surface,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                options.forEachIndexed { index, (label, description) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selected,
                            onClick = { onSelect(index) },
                            colors = RadioButtonDefaults.colors(selectedColor = LyraColors.Accent, unselectedColor = LyraColors.TextTertiary),
                        )
                        Column {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge) } },
    )
}
