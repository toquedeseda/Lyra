package com.lyra.desktop.system

import com.lyra.desktop.AppContainer
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.model.remoteId
import com.lyra.music.data.source.innertube.hiResArtwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Lleva lo que suena a Windows (teclas y tarjeta de volumen) y a Discord. */
object Integrations {

    fun start(app: AppContainer) {
        app.scope.launch {
            combine(app.player.state, app.settings.flow) { state, settings ->
                Triple(state.current?.song, state.isPlaying, settings.discordPresence)
            }.distinctUntilChanged().collect { (song, playing, discord) ->
                MediaControls.update(song?.title, song?.artistsText, song?.album?.title, song?.thumbnailUrl?.let { hiResArtwork(it, 600) ?: it })
                MediaControls.setPlaying(if (song == null) null else playing)
                if (discord && song != null && playing) {
                    // Un momento, para que la posición y la duración ya sean las de la canción nueva.
                    delay(1_200)
                    showOnDiscord(app, song)
                } else {
                    DiscordPresence.clear()
                }
            }
        }
        // Si se mueve la barra, el tiempo de Discord se corrige.
        app.scope.launch {
            var expectedStart = 0L
            while (isActive) {
                delay(10_000)
                val state = app.player.state.value
                val song = state.current?.song ?: continue
                if (!state.isPlaying || !app.settings.current.discordPresence) continue
                val start = System.currentTimeMillis() - app.player.positionMs
                if (abs(start - expectedStart) > 3_000) {
                    expectedStart = start
                    showOnDiscord(app, song)
                }
            }
        }
    }

    private fun showOnDiscord(app: AppContainer, song: Song) {
        val link = when (song.source) {
            Source.YOUTUBE -> "https://music.youtube.com/watch?v=" + song.id.remoteId()
            Source.SOUNDCLOUD -> "https://soundcloud.com/" + song.id.remoteId()
        }
        DiscordPresence.show(
            DiscordPresence.Track(
                title = song.title,
                artists = song.artistsText,
                album = song.album?.title,
                coverUrl = song.thumbnailUrl?.let { hiResArtwork(it, 512) ?: it },
                link = link,
                startedAt = System.currentTimeMillis() - app.player.positionMs,
                durationMs = app.player.durationMs.takeIf { it > 0 } ?: song.durationMs,
            ),
        )
    }
}
