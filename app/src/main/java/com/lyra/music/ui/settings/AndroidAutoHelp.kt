package com.lyra.music.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lyra.music.ui.theme.LyraColors

private const val ANDROID_AUTO = "com.google.android.projection.gearhead"

/** Android Auto está en el móvil (en Android 10 o más viene ya dentro del sistema). */
fun hasAndroidAuto(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(ANDROID_AUTO, 0) }.isSuccess

/**
 * Abre los ajustes de Android Auto: por la misma puerta que usan los Ajustes del móvil y, si no,
 * la app o su ficha. Si no está instalado, su página de Google Play.
 */
fun openAndroidAuto(context: Context): Boolean {
    val pm = context.packageManager
    if (hasAndroidAuto(context)) {
        val candidates = listOfNotNull(
            Intent("com.android.settings.action.IA_SETTINGS").setPackage(ANDROID_AUTO),
            Intent("com.android.settings.action.SETTINGS").setPackage(ANDROID_AUTO),
            pm.getLaunchIntentForPackage(ANDROID_AUTO),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO")),
        )
        for (intent in candidates) {
            if (intent.resolveActivity(pm) == null) continue
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
    }
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ANDROID_AUTO")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/**
 * Por qué no sale Lyra en el coche y cómo arreglarlo: Android Auto esconde las apps que no vienen
 * de Google Play hasta que se activan las «Fuentes desconocidas» en sus ajustes para desarrolladores.
 */
@Composable
fun AndroidAutoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val installed = hasAndroidAuto(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyraColors.Surface,
        title = { Text("Lyra en el coche", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Android Auto esconde las apps que no vienen de Google Play. Lyra funciona en el coche, pero hay que dejar que salga (solo una vez):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyraColors.TextSecondary,
                )
                Step(1, "Abre los ajustes de Android Auto (con el botón de abajo, o en los Ajustes del móvil busca «Android Auto»).")
                Step(2, "Baja del todo y toca «Versión» unas 10 veces, hasta que pregunte si permites los ajustes para desarrolladores → «Aceptar».")
                Step(3, "Arriba a la derecha, ⋮ → «Ajustes para desarrolladores».")
                Step(4, "Activa «Fuentes desconocidas».")
                Step(5, "Desconecta el móvil del coche y vuelve a conectarlo. Si aún no sale, en Android Auto → «Personalizar menú de aplicaciones», marca Lyra.")
                if (!installed) {
                    Text(
                        "No encuentro Android Auto en este móvil: el botón abre su página en Google Play.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LyraColors.TextTertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openAndroidAuto(context)
                onDismiss()
            }) { Text(if (installed) "Abrir Android Auto" else "Instalar Android Auto", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

@Composable
private fun Step(number: Int, text: String) {
    Row {
        Text("$number.", style = MaterialTheme.typography.bodyMedium, color = LyraColors.Accent, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
