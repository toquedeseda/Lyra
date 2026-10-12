package com.lyra.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lyra.desktop.BuildInfo
import com.lyra.desktop.ErrorLog
import com.lyra.desktop.ui.components.FilledPill
import com.lyra.desktop.ui.components.LyraTextField
import com.lyra.desktop.ui.components.OutlinePill
import com.lyra.music.data.feedback.Feedback
import com.lyra.music.data.feedback.FeedbackApp
import com.lyra.music.data.feedback.FeedbackDevice
import com.lyra.music.data.feedback.FeedbackSender
import kotlinx.coroutines.launch

/** Botón flotante «Ayúdanos» (abajo a la derecha del Inicio): abre el formulario de errores e ideas. */
@Composable
fun HelpUsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(LyraColors.Accent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Campaign, null, tint = LyraColors.OnAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Ayúdanos", color = LyraColors.OnAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * «Ayúdanos»: la persona cuenta qué falla o qué idea tiene y le llega al servidor de la web (y de
 * ahí al desarrollador). Si quiere, va también el informe de errores (versión, Windows y los
 * últimos fallos; nada personal).
 */
@Composable
fun HelpUsDialog() {
    val actions = LocalActions.current
    val app = actions.app
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(Feedback.Kind.PROBLEM) }
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var withReport by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val close = { actions.helpUsOpen = false }

    fun send() {
        if (sending || message.trim().length < 3) return
        sending = true
        error = null
        scope.launch {
            val feedback = Feedback(
                tipo = kind.code,
                mensaje = message,
                contacto = contact,
                app = FeedbackApp("windows", BuildInfo.VERSION),
                dispositivo = FeedbackDevice(sistema = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"),
                informe = if (withReport) ErrorLog.report() else null,
            )
            runCatching { app.feedback.send(feedback) }
                .onSuccess { sent = true }
                .onFailure { error = it.message ?: "No se pudo enviar. Prueba otra vez en un momento." }
            sending = false
        }
    }

    OverlayDialog(onDismiss = close, width = 520.dp) {
        if (sent) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.CheckCircle, null, tint = LyraColors.Accent, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("¡Gracias!", style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ya nos ha llegado. Lo leemos todo y sirve para que Lyra vaya cada vez mejor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyraColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                FilledPill("Cerrar", onClick = close)
            }
            return@OverlayDialog
        }
        Column {
            Text("Ayúdanos a mejorar Lyra", style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Cuéntanos qué falla o qué te gustaría. Nos llega al momento y lo leemos todo.",
                style = MaterialTheme.typography.bodyMedium,
                color = LyraColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Feedback.Kind.entries.forEach { option ->
                    OutlinePill(option.label, onClick = { kind = option }, selected = kind == option)
                }
            }
            Spacer(Modifier.height(12.dp))
            LyraTextField(
                message,
                { message = it.take(FeedbackSender.MAX_MESSAGE) },
                placeholderFor(kind),
                Modifier.fillMaxWidth().heightIn(min = 120.dp),
                focusRequester = focus,
                singleLine = false,
            )
            Spacer(Modifier.height(10.dp))
            LyraTextField(contact, { contact = it.take(FeedbackSender.MAX_CONTACT) }, "Tu nombre o cómo hablar contigo (si quieres)", Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mandar también el informe de errores", style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextPrimary)
                    Text(
                        "La versión de Lyra, la de Windows y los últimos fallos. Nada personal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LyraColors.TextSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = withReport,
                    onCheckedChange = { withReport = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = LyraColors.Accent, checkedThumbColor = LyraColors.OnAccent),
                )
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = LyraColors.Like)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = close) { Text("Cancelar", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.width(8.dp))
                FilledPill(if (sending) "Enviando…" else "Enviar", onClick = ::send, enabled = !sending && message.trim().length >= 3)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private fun placeholderFor(kind: Feedback.Kind) = when (kind) {
    Feedback.Kind.PROBLEM -> "¿Qué pasó y qué hacías justo antes? Por ejemplo: «al añadir una canción a la cola se pone en otro sitio»."
    Feedback.Kind.IDEA -> "¿Qué te gustaría que tuviera Lyra o que hiciera de otra forma?"
    Feedback.Kind.OTHER -> "Lo que quieras contarnos."
}

/**
 * El aviso grande que sale una vez al actualizar a la versión con «Ayúdanos», para que la gente
 * sepa que existe el botón y para qué sirve.
 */
@Composable
fun HelpUsIntro(onClose: () -> Unit) {
    val actions = LocalActions.current
    OverlayDialog(onDismiss = onClose, width = 480.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(LyraColors.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Campaign, null, tint = LyraColors.OnAccent, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("¡Ayúdanos a mejorar Lyra!", style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Ahora hay un botón «Ayúdanos» abajo a la derecha del Inicio. Si algo falla o se te ocurre una idea, cuéntanoslo desde ahí: nos llega al momento y lo leemos todo.",
                style = MaterialTheme.typography.bodyMedium,
                color = LyraColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text("También está en Ajustes.", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Ahora no", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge) }
                FilledPill("Contarnos algo", onClick = {
                    onClose()
                    actions.helpUsOpen = true
                })
            }
        }
    }
}
