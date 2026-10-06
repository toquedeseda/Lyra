package com.lyra.music.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.settings.AppSettings
import com.lyra.music.data.settings.IslandMode
import com.lyra.music.island.EqualizerBars
import com.lyra.music.island.IslandController
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.PageHeader
import com.lyra.music.ui.theme.LyraColors
import com.lyra.music.BuildConfig

@UnstableApi
@Composable
fun IslandScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val container = actions.container
    val context = LocalContext.current
    val s by container.settings.flow.collectAsState()

    // Los permisos se cambian fuera de la app: se vuelven a mirar al volver.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val accessibilityOn = remember(refresh) { IslandController.accessibilityEnabled(context) }
    val overlayOn = remember(refresh) { container.island.canDrawOverlays() }
    var modeDialog by remember { mutableStateOf(false) }

    fun update(transform: (AppSettings) -> AppSettings) = actions.launch { container.settings.update(transform) }

    Column(Modifier.fillMaxSize()) {
        val nextToCamera = BuildConfig.ISLAND_NEXT_TO_CAMERA
        PageHeader("Isla flotante", if (nextToCamera) "Lo que suena, junto a la cámara, fuera de Lyra." else "Lo que suena, arriba de la pantalla, fuera de Lyra.")
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            Preview(s)
            Text(
                if (nextToCamera) {
                    "Aparece junto a la cámara cuando sales de Lyra con música sonando. Tócala para desplegarla con los controles; mantenla pulsada para abrir Lyra."
                } else {
                    "Aparece arriba, justo debajo de la barra de estado, cuando sales de Lyra con música sonando. Tócala para desplegarla con los controles; mantenla pulsada para abrir Lyra."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = LyraColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            SwitchRow("Activar la isla", null, s.islandEnabled) { value -> update { it.copy(islandEnabled = value) } }

            Group("Permisos")
            if (nextToCamera) NavRow(
                "Accesibilidad (recomendado)",
                if (accessibilityOn) "Activado ✓ — la isla puede ir encima de la barra de estado, junto a la cámara"
                else "Desactivado. Ajustes → Accesibilidad → Apps instaladas → Lyra isla → Activar",
            ) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (nextToCamera && !accessibilityOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                NavRow(
                    "¿Sale en gris?",
                    "Android bloquea la accesibilidad de apps instaladas a mano. Abre la info de Lyra → menú ⋮ (arriba a la derecha) → «Permitir ajustes restringidos», y vuelve a intentarlo.",
                ) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            }
            NavRow(
                "Mostrar sobre otras apps",
                when {
                    !overlayOn -> "No permitido: actívalo para que salga la isla"
                    nextToCamera -> "Permitido ✓ — se usa si la accesibilidad está apagada (la isla va justo debajo de la barra de estado)"
                    else -> "Permitido ✓"
                },
            ) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
            if (nextToCamera) NavRow("Modo", modeLabel(s.islandMode)) { modeDialog = true }

            Group("Tamaño y posición")
            SliderRow("Ancho", "${s.islandWidth} dp", s.islandWidth.toFloat(), 80f..240f, 0) { v -> update { it.copy(islandWidth = v.toInt()) } }
            SliderRow("Alto", "${s.islandHeight} dp", s.islandHeight.toFloat(), 24f..52f, 0) { v -> update { it.copy(islandHeight = v.toInt()) } }
            SliderRow("Mover a los lados", "${s.islandOffsetX} dp", s.islandOffsetX.toFloat(), -140f..140f, 0) { v -> update { it.copy(islandOffsetX = v.toInt()) } }
            SliderRow("Mover arriba/abajo", "${s.islandOffsetY} dp", s.islandOffsetY.toFloat(), -30f..90f, 0) { v -> update { it.copy(islandOffsetY = v.toInt()) } }
            Text(
                if (nextToCamera) "Consejo: pon música, sal de Lyra y mira dónde queda. Vuelve y ajústala hasta que rodee la cámara."
                else "Consejo: pon música, sal de Lyra y mira dónde queda. Vuelve y ajústala a tu gusto.",
                style = MaterialTheme.typography.bodySmall,
                color = LyraColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    if (modeDialog) {
        ChoiceDialog(
            title = "Modo de la isla",
            options = IslandMode.entries.map { modeLabel(it) to modeDescription(it) },
            selected = IslandMode.entries.indexOf(s.islandMode),
            onDismiss = { modeDialog = false },
        ) { index ->
            update { it.copy(islandMode = IslandMode.entries[index]) }
            modeDialog = false
        }
    }
}

@Composable
private fun Preview(s: AppSettings) {
    // Una "barra de estado" de mentira con la cámara, para ver el tamaño.
    Box(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LyraColors.SurfaceHigh),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("12:30", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text("5G  80%", style = MaterialTheme.typography.labelMedium)
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(x = (s.islandOffsetX / 2).dp, y = (8 + s.islandOffsetY / 2).dp)
                .width((s.islandWidth / 1.4f).dp)
                .height((s.islandHeight / 1.4f).dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(((s.islandHeight - 12) / 1.4f).coerceAtLeast(10f).dp)
                    .clip(CircleShape)
                    .background(LyraColors.TextTertiary),
            )
            EqualizerBars(true, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(10.dp), color = LyraColors.Accent)
        }
        // La cámara
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = 14.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(Color(0xFF0A0A0A)),
        )
    }
}

private fun modeLabel(mode: IslandMode) = when (mode) {
    IslandMode.AUTO -> "Automático"
    IslandMode.ACCESSIBILITY -> "Solo accesibilidad"
    IslandMode.OVERLAY -> "Solo superposición"
}

private fun modeDescription(mode: IslandMode) = when (mode) {
    IslandMode.AUTO -> "Accesibilidad si está activada; si no, superposición"
    IslandMode.ACCESSIBILITY -> "Encima de la barra de estado, junto a la cámara"
    IslandMode.OVERLAY -> "Debajo de la barra de estado, sin accesibilidad"
}
