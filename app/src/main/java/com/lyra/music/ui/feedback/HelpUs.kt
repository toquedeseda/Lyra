package com.lyra.music.ui.feedback

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lyra.music.BuildConfig
import com.lyra.music.core.ErrorLog
import com.lyra.music.data.feedback.Feedback
import com.lyra.music.data.feedback.FeedbackApp
import com.lyra.music.data.feedback.FeedbackDevice
import com.lyra.music.data.feedback.FeedbackSender
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.pressable
import com.lyra.music.ui.theme.LyraColors
import kotlinx.coroutines.launch

/** Botón flotante «Ayúdanos» (abajo a la derecha del Inicio): abre el formulario de errores e ideas. */
@Composable
fun HelpUsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(LyraColors.Accent)
            .pressable(pressedScale = 0.94f, onClick = onClick)
            .padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Campaign, null, tint = LyraColors.OnAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Ayúdanos", color = LyraColors.OnAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * «Ayúdanos»: la persona cuenta qué falla o qué idea tiene y le llega al servidor de la web (y de
 * ahí al desarrollador). Si quiere, va también el informe de errores (versión, móvil y los
 * últimos fallos; nada personal).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpUsSheet(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val container = actions.container
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by rememberSaveable { mutableStateOf(Feedback.Kind.PROBLEM) }
    var message by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var withReport by rememberSaveable { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by rememberSaveable { mutableStateOf(false) }

    fun send() {
        sending = true
        error = null
        scope.launch {
            val feedback = Feedback(
                tipo = kind.code,
                mensaje = message,
                contacto = contact,
                app = FeedbackApp("android", BuildConfig.VERSION_NAME, BuildConfig.UPDATE_ASSET),
                dispositivo = FeedbackDevice(Build.MANUFACTURER, Build.MODEL, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                informe = if (withReport) ErrorLog.report() else null,
            )
            runCatching { container.feedback.send(feedback) }
                .onSuccess { sent = true }
                .onFailure { error = it.message ?: "No se pudo enviar. Prueba otra vez en un momento." }
            sending = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = LyraColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
        ) {
            if (sent) {
                Thanks(onClose = onDismiss)
                return@Column
            }
            Text("Ayúdanos a mejorar Lyra", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text("Cuéntanos qué falla o qué te gustaría. Nos llega al momento y lo leemos todo.", color = LyraColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Feedback.Kind.entries.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LyraColors.Accent,
                            selectedLabelColor = LyraColors.OnAccent,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(FeedbackSender.MAX_MESSAGE) },
                placeholder = { Text(placeholderFor(kind)) },
                minLines = 5,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it.take(FeedbackSender.MAX_CONTACT) },
                label = { Text("Tu nombre o cómo hablar contigo (si quieres)") },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mandar también el informe de errores", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "La versión de Lyra, el modelo del móvil y los últimos fallos. Nada personal.",
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
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = ::send,
                enabled = !sending && message.trim().length >= 3,
                colors = ButtonDefaults.buttonColors(containerColor = LyraColors.Accent, contentColor = LyraColors.OnAccent),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), color = LyraColors.OnAccent, strokeWidth = 2.dp)
                else Text("Enviar", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Thanks(onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CheckCircle, null, tint = LyraColors.Accent, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(14.dp))
        Text("¡Gracias!", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Ya nos ha llegado. Lo leemos todo y sirve para que Lyra vaya cada vez mejor.",
            color = LyraColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = LyraColors.Accent, contentColor = LyraColors.OnAccent),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Cerrar", style = MaterialTheme.typography.titleMedium) }
    }
}

private fun placeholderFor(kind: Feedback.Kind) = when (kind) {
    Feedback.Kind.PROBLEM -> "¿Qué pasó y qué hacías justo antes? Por ejemplo: «al añadir una canción a la cola se pone en otro sitio»."
    Feedback.Kind.IDEA -> "¿Qué te gustaría que tuviera Lyra o que hiciera de otra forma?"
    Feedback.Kind.OTHER -> "Lo que quieras contarnos."
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LyraColors.Accent,
    cursorColor = LyraColors.Accent,
    focusedLabelColor = LyraColors.Accent,
)

/**
 * El aviso grande que sale una vez al actualizar a la versión con «Ayúdanos», para que la gente
 * sepa que existe el botón y para qué sirve.
 */
@Composable
fun HelpUsIntroDialog(onTry: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = LyraColors.Surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, LyraColors.Border),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(LyraColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Campaign, null, tint = LyraColors.OnAccent, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text("¡Ayúdanos a mejorar Lyra!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ahora hay un botón «Ayúdanos» abajo a la derecha del Inicio. Si algo falla o se te ocurre una idea, cuéntanoslo desde ahí: nos llega al momento y lo leemos todo.",
                    color = LyraColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text("También está en Ajustes.", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary)
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onTry,
                    colors = ButtonDefaults.buttonColors(containerColor = LyraColors.Accent, contentColor = LyraColors.OnAccent),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Contarnos algo", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Ahora no", color = LyraColors.TextSecondary)
                }
            }
        }
    }
}
