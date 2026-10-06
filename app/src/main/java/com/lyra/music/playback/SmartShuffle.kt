package com.lyra.music.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.Recommender
import com.lyra.music.playback.MediaItems.isRadio
import com.lyra.music.playback.MediaItems.isRecommended
import com.lyra.music.playback.MediaItems.toMediaItem
import com.lyra.music.playback.MediaItems.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Aleatorio inteligente: con el aleatorio puesto, cada pocas canciones se cuela una
 * recomendada parecida a la lista (marcada en la cola). Al quitar el aleatorio o
 * el modo inteligente, las recomendadas que no han sonado desaparecen.
 */
@UnstableApi
class SmartShuffleController(
    private val player: ExoPlayer,
    private val recommender: Recommender,
    private val downloads: DownloadRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    private var job: Job? = null
    private var busy = false
    private var jobSignature: String? = null

    init {
        player.addListener(this)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = update()

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || busy) return
        // Si ha llegado otra cola, lo que se estaba buscando era para la anterior: fuera.
        if (job?.isActive == true && signature() != jobSignature) job?.cancel()
        update()
    }

    /** Huella de la cola (sus primeras canciones propias): cambia si se pone otra lista. */
    private fun signature(): String = (0 until player.mediaItemCount).asSequence()
        .map { player.getMediaItemAt(it) }
        .filterNot { it.isRecommended() || it.isRadio() }
        .take(20)
        .joinToString("|") { it.mediaId }

    private fun update() {
        if (enabled && player.shuffleModeEnabled) fill() else removeRecommended()
    }

    private fun hasRecommended() = (0 until player.mediaItemCount).any { player.getMediaItemAt(it).isRecommended() }

    private fun fill() {
        if (job?.isActive == true || player.mediaItemCount < MIN_QUEUE || hasRecommended()) return
        val songs = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toSong() }
        val wanted = signature()
        jobSignature = wanted
        job = scope.launch {
            val count = (songs.size / EVERY).coerceIn(3, 40)
            val recommended = runCatching { recommender.similarTo(songs, count = count, seeds = 5) }.getOrDefault(emptyList())
            // Mientras se buscaban pudo cambiar la cola o quitarse el aleatorio.
            if (recommended.isEmpty() || !enabled || !player.shuffleModeEnabled || hasRecommended()) return@launch
            if (signature() != wanted) return@launch
            insert(recommended)
        }
    }

    /** Añade las recomendadas y las reparte por el orden aleatorio, detrás de la que suena. */
    private fun insert(songs: List<Song>) {
        val before = playOrder()
        val position = before.indexOf(player.currentMediaItemIndex)
        val head = if (position >= 0) before.take(position + 1) else emptyList()
        val rest = if (position >= 0) before.drop(position + 1) else before
        val usable = songs.take((rest.size / EVERY).coerceAtLeast(1))
        val firstNew = player.mediaItemCount
        busy = true
        try {
            player.addMediaItems(usable.map { it.toMediaItem(downloads.localCover(it.id), recommended = true) })
            val added = (firstNew until player.mediaItemCount).toList()
            val order = head + interleave(rest, added, EVERY)
            if (order.size == player.mediaItemCount) {
                player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
            }
        } finally {
            busy = false
        }
    }

    private fun removeRecommended() {
        job?.cancel()
        if (player.mediaItemCount == 0) return
        val current = player.currentMediaItemIndex
        val indices = (0 until player.mediaItemCount).filter { it != current && player.getMediaItemAt(it).isRecommended() }
        if (indices.isEmpty()) return
        busy = true
        try {
            indices.asReversed().forEach { player.removeMediaItem(it) }
        } finally {
            busy = false
        }
    }

    /** Orden real en el que van a sonar (índices), respetando el aleatorio. */
    private fun playOrder(): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = player.shuffleModeEnabled
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET && order.size <= timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return order
    }

    fun release() {
        job?.cancel()
        player.removeListener(this)
    }

    companion object {
        private const val MIN_QUEUE = 4

        /** Una recomendada cada tantas canciones de la lista. */
        const val EVERY = 3

        /** Mete un elemento de [extra] cada [every] de [base]; lo que sobre va al final. */
        fun <T> interleave(base: List<T>, extra: List<T>, every: Int): List<T> {
            val result = ArrayList<T>(base.size + extra.size)
            var next = 0
            base.forEachIndexed { index, item ->
                result += item
                if ((index + 1) % every == 0 && next < extra.size) result += extra[next++]
            }
            while (next < extra.size) result += extra[next++]
            return result
        }
    }
}
