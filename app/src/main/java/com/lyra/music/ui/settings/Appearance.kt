package com.lyra.music.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.lyra.music.R
import com.lyra.music.data.settings.AccentColor
import com.lyra.music.ui.theme.LyraColors
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.res.ResourcesCompat

/** Iconos de la app a elegir: cada uno es una entrada del cajón de apps; solo una está activa. */
enum class AppIcon(val alias: String, val label: String, @DrawableRes val preview: Int) {
    CLASICO("IconoClasico", "Clásico", R.mipmap.ic_launcher),
    HUESO("IconoHueso", "Hueso", R.mipmap.ic_launcher_hueso),
    ROJO("IconoRojo", "Rojo", R.mipmap.ic_launcher_rojo),
    NOCHE("IconoNoche", "Noche", R.mipmap.ic_launcher_noche),
    ATARDECER("IconoAtardecer", "Atardecer", R.mipmap.ic_launcher_atardecer),
}

object AppIcons {
    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, "com.lyra.music.${icon.alias}")

    fun current(context: Context): AppIcon = AppIcon.entries.firstOrNull { isEnabled(context, it) } ?: AppIcon.CLASICO

    private fun isEnabled(context: Context, icon: AppIcon): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.CLASICO // como viene en el manifiesto
            else -> false
        }

    /** Primero se enciende el nuevo y luego se apagan los demás: nunca se queda Lyra sin icono. */
    fun set(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        AppIcon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}

/** Color de Lyra: se ve al momento al tocarlo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentDialog(current: AccentColor, onPick: (AccentColor) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyraColors.Surface,
        title = { Text("Color de Lyra", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                Text(
                    "Botones, interruptores y detalles. El corazón de «Me gusta» sigue siendo rojo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LyraColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AccentColor.entries.forEach { color ->
                        val selected = color == current
                        Column(
                            Modifier
                                .width(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(color) }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
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
                            Text(
                                color.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) LyraColors.TextPrimary else LyraColors.TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge) } },
    )
}

/** Icono de la app en la pantalla de inicio del móvil. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppIconDialog(current: AppIcon, onPick: (AppIcon) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val sizePx = with(LocalDensity.current) { 56.dp.roundToPx() }
    // El icono tal como lo dibuja el móvil (con la forma de sus iconos).
    val previews = remember(resources, sizePx) {
        AppIcon.entries.associateWith { icon ->
            ResourcesCompat.getDrawable(resources, icon.preview, null)?.toBitmap(sizePx, sizePx)?.asImageBitmap()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyraColors.Surface,
        title = { Text("Icono de la app", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppIcon.entries.forEach { icon ->
                        val selected = icon == current
                        Column(
                            Modifier
                                .width(72.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) LyraColors.SurfaceHigher else Color.Transparent)
                                .clickable { onPick(icon) }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            previews[icon]?.let { Image(it, icon.label, Modifier.size(56.dp)) }
                            Text(
                                icon.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) LyraColors.TextPrimary else LyraColors.TextSecondary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                Text(
                    "El móvil puede tardar unos segundos en cambiarlo. Si tienes Lyra en la pantalla de inicio y no cambia, quítala y vuelve a ponerla desde el cajón de apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LyraColors.TextSecondary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge) } },
    )
}
