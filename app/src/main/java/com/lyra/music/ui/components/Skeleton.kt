package com.lyra.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.ui.theme.LyraColors

/**
 * Carga "elegante": en vez de un circulito girando se ven las formas de lo que va a
 * aparecer (portada, título, filas…) con un brillo suave que las recorre.
 */

/** Avance del brillo (0 → 1, en bucle), compartido por todas las formas de una pantalla. */
@Composable
fun rememberShimmer(): State<Float> = rememberInfiniteTransition(label = "brillo").animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(1_300, easing = LinearEasing), RepeatMode.Restart),
    label = "brillo",
)

/** Forma gris con el brillo pasando (diagonal, de izquierda a derecha). */
fun Modifier.skeleton(shimmer: State<Float>, shape: Shape = RoundedCornerShape(6.dp)): Modifier = this
    .clip(shape)
    .drawBehind {
        drawRect(LyraColors.SurfaceHigh)
        val width = size.width.coerceAtLeast(1f)
        // El brillo cruza de fuera a fuera: empieza a la izquierda de la forma y sale por la derecha.
        val x = -width + shimmer.value * width * 3f
        drawRect(
            Brush.linearGradient(
                0f to LyraColors.SurfaceHigh,
                0.5f to LyraColors.SurfaceHigher,
                1f to LyraColors.SurfaceHigh,
                start = Offset(x - width * 0.6f, 0f),
                end = Offset(x, size.height),
            ),
        )
    }

/** Barra de texto de mentira. */
@Composable
private fun Line(shimmer: State<Float>, width: Dp, height: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(width)
            .height(height)
            .skeleton(shimmer, RoundedCornerShape(50)),
    )
}

/** Fila de canción de mentira: portada pequeña y dos líneas. */
@Composable
private fun RowSkeleton(shimmer: State<Float>, index: Int, round: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).skeleton(shimmer, if (round) CircleShape else RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Anchos distintos para que no parezca una tabla.
            Line(shimmer, listOf(180, 140, 210, 160, 120, 190)[index % 6].dp, 13.dp)
            Line(shimmer, listOf(110, 90, 130, 80, 100, 70)[index % 6].dp, 10.dp)
        }
    }
}

/** Lista de filas mientras carga (resultados de búsqueda…). */
@Composable
fun RowsSkeleton(modifier: Modifier = Modifier, rows: Int = 8, round: Boolean = false) {
    val shimmer = rememberShimmer()
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { RowSkeleton(shimmer, it, round) }
    }
}

/**
 * Álbum o playlist cargando: portada grande, título, botones y canciones. Si se abrió
 * tocando su portada ([coverId]), esa portada ya se ve (y llega volando, si está elegido).
 */
@UnstableApi
@Composable
fun CollectionSkeleton(coverId: String? = null) {
    val shimmer = rememberShimmer()
    val artwork = CoverFlight.artworkFor(coverId)
    Column(Modifier.fillMaxSize()) {
        BackBar()
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (artwork != null) {
                Artwork(artwork, Modifier.size(232.dp).flyingCoverTarget(coverId, RoundedCornerShape(16.dp)), RoundedCornerShape(16.dp))
            } else {
                Box(Modifier.size(232.dp).skeleton(shimmer, RoundedCornerShape(16.dp)))
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Line(shimmer, 90.dp, 10.dp)
            Line(shimmer, 230.dp, 30.dp)
            Line(shimmer, 140.dp, 12.dp)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).skeleton(shimmer, CircleShape))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(40.dp).skeleton(shimmer, CircleShape))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(56.dp).skeleton(shimmer, CircleShape))
        }
        repeat(6) { RowSkeleton(shimmer, it) }
    }
}

/** Artista cargando: foto a todo lo ancho, nombre y canciones. */
@UnstableApi
@Composable
fun ArtistSkeleton() {
    val shimmer = rememberShimmer()
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.1f)
                .skeleton(shimmer, RoundedCornerShape(0.dp)),
        ) {
            BackBar()
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Line(shimmer, 70.dp, 10.dp)
                Line(shimmer, 220.dp, 36.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        repeat(5) { RowSkeleton(shimmer, it) }
    }
}

/** Secciones del Inicio (o de Explorar) cargando: título y un carrusel de tarjetas. */
@Composable
fun SectionsSkeleton(modifier: Modifier = Modifier, sections: Int = 2) {
    val shimmer = rememberShimmer()
    Column(modifier.fillMaxWidth()) {
        repeat(sections) { section ->
            Line(shimmer, if (section % 2 == 0) 170.dp else 130.dp, 18.dp, Modifier.padding(start = 20.dp, top = 22.dp, bottom = 14.dp))
            Row(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(3) { card ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(156.dp).skeleton(shimmer, RoundedCornerShape(16.dp)))
                        Line(shimmer, listOf(120, 100, 130)[card].dp, 12.dp)
                        Line(shimmer, listOf(80, 95, 70)[card].dp, 10.dp)
                    }
                }
            }
        }
    }
}

/**
 * Pasa de la carga al contenido con un fundido (solo al cambiar de estado: cuando la
 * lista ya está y llegan más canciones no se vuelve a animar).
 */
@Composable
fun <T> LoadableCrossfade(state: Loadable<T>, content: @Composable (Loadable<T>) -> Unit) {
    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = { (fadeIn(tween(280)) togetherWith fadeOut(tween(160))).using(null) },
        label = "carga",
    ) { current -> content(current) }
}
