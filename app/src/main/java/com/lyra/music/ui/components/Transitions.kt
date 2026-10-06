package com.lyra.music.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.lyra.music.data.settings.ScreenTransition

/**
 * Animaciones al cambiar de pantalla (se eligen en Ajustes → Aspecto):
 *  - Suave: la de siempre, mejorada (entra deslizándose un poco y la anterior se aparta).
 *  - Portada que vuela: la portada que tocas viaja y crece hasta la cabecera.
 *  - Sin animación.
 */

/** Ámbito de la animación compartida de toda la app (lo da LyraRoot). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Animación de entrada/salida de la pantalla actual (la da cada destino del NavHost). */
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

val LocalScreenTransition = staticCompositionLocalOf { ScreenTransition.SMOOTH }

/**
 * La portada que se ha tocado ("id@sitio"). Solo esa vuela: si el mismo álbum sale dos
 * veces en el Inicio, no se lían.
 */
object CoverFlight {
    var tapped by mutableStateOf<String?>(null)

    /** Su imagen: la pantalla que se abre la enseña ya mientras carga (y la portada vuela hasta ahí). */
    var artwork by mutableStateOf<Any?>(null)

    fun take(tag: String?, image: Any?) {
        tapped = tag
        artwork = image
    }

    /** La imagen de la portada tocada, si es la de la lista [id]. */
    fun artworkFor(id: String?): Any? = artwork.takeIf { id != null && tapped?.substringBeforeLast('@') == id }
}

/** Etiqueta única de una portada en su sitio de la pantalla. */
@Composable
fun rememberCoverTag(id: String?): String? {
    val site = currentCompositeKeyHash
    return id?.let { "$it@$site" }
}

/** Portada de una tarjeta o fila: si es la que se tocó, vuela a la pantalla que se abre. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.flyingCover(tag: String?, shape: Shape): Modifier {
    if (LocalScreenTransition.current != ScreenTransition.COVER || tag == null || CoverFlight.tapped != tag) return this
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) {
        this@flyingCover.sharedElement(
            rememberSharedContentState("cover:$tag"),
            animatedVisibilityScope = nav,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/** Portada de la cabecera de la lista [id]: recibe la que viene volando (si se tocó una suya). */
@Composable
fun Modifier.flyingCoverTarget(id: String?, shape: Shape): Modifier {
    val tapped = CoverFlight.tapped ?: return this
    if (id == null || tapped.substringBeforeLast('@') != id) return this
    return flyingCover(tapped, shape)
}

private val Smooth = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Entrada de una pantalla nueva. */
fun enterFor(style: ScreenTransition): EnterTransition = when (style) {
    ScreenTransition.SMOOTH -> fadeIn(tween(220, delayMillis = 30)) + slideInHorizontally(tween(360, easing = Smooth)) { it / 9 }
    ScreenTransition.COVER -> fadeIn(tween(320, easing = Smooth))
    ScreenTransition.NONE -> EnterTransition.None
}

/** La pantalla que se queda debajo al abrir otra. */
fun exitFor(style: ScreenTransition): ExitTransition = when (style) {
    ScreenTransition.SMOOTH -> fadeOut(tween(200)) + slideOutHorizontally(tween(360, easing = Smooth)) { -it / 20 }
    ScreenTransition.COVER -> fadeOut(tween(320, easing = Smooth))
    ScreenTransition.NONE -> ExitTransition.None
}

/** La pantalla de debajo al volver atrás. */
fun popEnterFor(style: ScreenTransition): EnterTransition = when (style) {
    ScreenTransition.SMOOTH -> fadeIn(tween(240)) + slideInHorizontally(tween(360, easing = Smooth)) { -it / 20 }
    ScreenTransition.COVER -> fadeIn(tween(320, easing = Smooth))
    ScreenTransition.NONE -> EnterTransition.None
}

/** La pantalla que se cierra al volver atrás. */
fun popExitFor(style: ScreenTransition): ExitTransition = when (style) {
    ScreenTransition.SMOOTH -> fadeOut(tween(180)) + slideOutHorizontally(tween(320, easing = Smooth)) { it / 9 }
    ScreenTransition.COVER -> fadeOut(tween(320, easing = Smooth))
    ScreenTransition.NONE -> ExitTransition.None
}
