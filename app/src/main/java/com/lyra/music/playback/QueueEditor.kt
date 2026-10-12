package com.lyra.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.lyra.music.playback.MediaItems.isQueued

/**
 * La cola como en Spotify, también con el aleatorio puesto:
 *  - «Reproducir a continuación»: justo detrás de la que suena.
 *  - «Añadir a la cola»: detrás de la que suena y de lo que ya añadiste tú, antes que el resto de la
 *    lista (antes iba al final de toda la lista: en una de 200 canciones no sonaba nunca).
 *  - Mover arrastrando: queda donde la sueltas.
 * Con aleatorio, Android mete lo nuevo en un sitio cualquiera del orden y mover reordenaba al azar;
 * aquí se recoloca el orden aleatorio a mano para que todo quede donde se ve en la cola.
 */
@UnstableApi
class QueueEditor(private val player: ExoPlayer) {

    /** El orden en el que van a sonar (índices de la lista), entero, con o sin aleatorio. */
    fun playOrder(): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        if (!player.shuffleModeEnabled) return (0 until player.mediaItemCount).toList()
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return order
    }

    /** Cuántas de las añadidas a mano van seguidas justo detrás de la que suena. */
    private fun queuedAfter(order: List<Int>, position: Int): Int {
        var count = 0
        while (position + 1 + count < order.size && player.getMediaItemAt(order[position + 1 + count]).isQueued()) count++
        return count
    }

    /** [next]: justo detrás de la que suena; si no, al final de lo añadido a mano. */
    fun insert(items: List<MediaItem>, next: Boolean) {
        if (items.isEmpty()) return
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items)
            player.prepare()
            player.play()
            return
        }
        val order = playOrder()
        val position = order.indexOf(player.currentMediaItemIndex).coerceAtLeast(0)
        val at = position + 1 + if (next) 0 else queuedAfter(order, position)
        if (!player.shuffleModeEnabled) {
            // Sin aleatorio, el orden es el de la lista: se meten ahí mismo.
            player.addMediaItems(at, items)
            return
        }
        val first = player.mediaItemCount
        player.addMediaItems(items)
        setOrder(insertedOrder(order, at, (first until first + items.size).toList()))
    }

    /** Mueve una de las que vienen: [from] y [to] cuentan desde la siguiente (0 = la siguiente). */
    fun moveUpcoming(from: Int, to: Int) {
        val order = playOrder()
        val position = order.indexOf(player.currentMediaItemIndex)
        if (position < 0) return
        val moved = movedOrder(order, position, from, to) ?: return
        if (!player.shuffleModeEnabled) {
            player.moveMediaItem(position + 1 + from, position + 1 + to)
            return
        }
        setOrder(moved)
    }

    /** Quita todo lo que viene después de la que suena (en el orden de verdad). */
    fun clearUpcoming() {
        val order = playOrder()
        val position = order.indexOf(player.currentMediaItemIndex)
        if (position < 0) return
        removalRanges(order.drop(position + 1)).forEach { player.removeMediaItems(it.first, it.last + 1) }
    }

    private fun setOrder(order: List<Int>) {
        if (order.size != player.mediaItemCount || order.toSet().size != order.size) return
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
    }

    companion object {
        /** El orden con [added] metidas en la posición [at]. */
        fun insertedOrder(order: List<Int>, at: Int, added: List<Int>): List<Int> =
            order.take(at) + added + order.drop(at)

        /**
         * El orden con una de las que vienen movida: [from] y [to] cuentan desde la siguiente a la que
         * está en [position]. Null si no tiene sentido (fuera de rango o sin cambio).
         */
        fun movedOrder(order: List<Int>, position: Int, from: Int, to: Int): List<Int>? {
            val upcoming = order.drop(position + 1).toMutableList()
            if (from == to || from !in upcoming.indices || to !in upcoming.indices) return null
            upcoming.add(to, upcoming.removeAt(from))
            return order.take(position + 1) + upcoming
        }

        /** Tramos seguidos de [indices], de atrás adelante: quitándolos así no se descolocan los demás. */
        fun removalRanges(indices: Collection<Int>): List<IntRange> {
            val sorted = indices.distinct().sortedDescending()
            val ranges = mutableListOf<IntRange>()
            var i = 0
            while (i < sorted.size) {
                val end = sorted[i]
                var start = end
                while (i + 1 < sorted.size && sorted[i + 1] == start - 1) start = sorted[++i]
                ranges += start..end
                i++
            }
            return ranges
        }
    }
}
