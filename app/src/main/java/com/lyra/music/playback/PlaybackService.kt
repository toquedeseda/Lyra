package com.lyra.music.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lyra.music.LyraApp
import com.lyra.music.MainActivity
import com.lyra.music.R
import com.lyra.music.data.model.Song
import com.lyra.music.data.settings.AppSettings
import com.lyra.music.playback.MediaItems.toMediaItem
import com.lyra.music.playback.MediaItems.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/** Órdenes propias que la interfaz y la notificación envían al servicio. */
object LyraCommands {
    const val ACTION_LIKE = "lyra.like"
    const val ACTION_START_RADIO = "lyra.radio"
    const val ACTION_PLAYLIST_RADIO = "lyra.playlist_radio"
    const val ACTION_NEW_QUEUE = "lyra.new_queue"
    const val ARG_SONG = "song"
    const val ARG_PLAYLIST = "playlist"
    const val ARG_SHUFFLE = "shuffle"

    val LIKE = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    val START_RADIO = SessionCommand(ACTION_START_RADIO, Bundle.EMPTY)
    val PLAYLIST_RADIO = SessionCommand(ACTION_PLAYLIST_RADIO, Bundle.EMPTY)
    val NEW_QUEUE = SessionCommand(ACTION_NEW_QUEUE, Bundle.EMPTY)
}

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private val container get() = (application as LyraApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var processor: LyraAudioProcessor
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var crossfade: CrossfadeController
    private lateinit var radio: RadioController
    private lateinit var tracker: PlaybackTracker
    private lateinit var queueStore: QueueStore
    private lateinit var autoLibrary: AutoLibrary
    private val searchResults = mutableMapOf<String, List<Song>>()
    private var retryCount = 0
    private var pendingShuffleStart = false
    private lateinit var headphones: HeadphonesWatcher
    private var widgetJob: kotlinx.coroutines.Job? = null

    /**
     * En aleatorio, la canción actual pasa a ser la primera del orden y el resto se
     * baraja detrás. Así suenan TODAS las de la lista (antes, las que quedaban
     * "antes" de la actual en el orden aleatorio no llegaban a sonar).
     */
    private fun shuffleFromCurrent() {
        val count = player.mediaItemCount
        if (count < 2) return
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val rest = (0 until count).filter { it != current }.shuffled()
        player.setShuffleOrder(DefaultShuffleOrder((listOf(current) + rest).toIntArray(), System.nanoTime()))
    }

    override fun onCreate() {
        super.onCreate()
        val c = container
        processor = LyraAudioProcessor().apply { config = c.settings.current.toFxConfig(); publishLevels = true }
        player = buildPlayer(processor, main = true)
        crossfade = CrossfadeController(
            main = player,
            tailFactory = {
                buildPlayer(LyraAudioProcessor().apply { config = processor.config }, main = false)
            },
            scope = scope,
            onNewTrack = { processor.onNewTrack() },
        )
        radio = RadioController(player, c.music, c.settings, c.downloads, c.library, scope)
        tracker = PlaybackTracker(player, c.library, scope)
        queueStore = QueueStore(File(filesDir, "queue.json"), scope)
        autoLibrary = AutoLibrary(this)

        player.addListener(ServiceListener())
        restoreQueue()

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(buttons(liked = false))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(LyraApp.CHANNEL_PLAYBACK)
                .setChannelName(R.string.channel_playback)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        observeSettings()
        observeLike()
        c.island.attach(player)
        headphones = HeadphonesWatcher(this, ::resumeOnHeadphones).also { it.start() }
        updateWidget()
    }

    /** El widget refleja la canción y si suena (con un pequeño margen para agrupar cambios). */
    private fun updateWidget() {
        widgetJob?.cancel()
        widgetJob = scope.launch {
            delay(300)
            val item = player.currentMediaItem
            runCatching {
                com.lyra.music.widget.WidgetUpdater.push(this@PlaybackService, item?.toSong(), item?.mediaMetadata?.artworkUri, player.playWhenReady)
            }
        }
    }

    /** Se conectaron unos auriculares: si la música se paró al quitarlos, sigue. */
    private fun resumeOnHeadphones() {
        if (!container.settings.current.resumeOnConnect || !HeadsetResume.pending(this)) return
        if (player.playWhenReady || player.mediaItemCount == 0) return
        scope.launch {
            delay(1_500) // Deja que Android pase el audio a los auriculares.
            if (player.playWhenReady || !HeadsetResume.pending(this@PlaybackService)) return@launch
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    private fun buildPlayer(fx: LyraAudioProcessor, main: Boolean): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(fx))
                .build()
        }.setEnableDecoderFallback(true)

        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(container.dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ main,
            )
            .setHandleAudioBecomingNoisy(main)
            .setWakeMode(if (main) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 180_000, 1_000, 2_500)
                    .build(),
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    private fun AppSettings.toFxConfig() = AudioFxConfig(
        eqEnabled = eqEnabled,
        bandsDb = eqBands,
        preampDb = eqPreamp,
        normalize = normalizeVolume,
    )

    private fun observeSettings() {
        scope.launch {
            container.settings.flow.collect { settings ->
                processor.config = settings.toFxConfig()
                crossfade.durationMs = settings.crossfadeSeconds * 1000L
                player.skipSilenceEnabled = settings.skipSilence
            }
        }
    }

    /** El corazón de la notificación refleja si la canción actual está en "Me gusta". */
    private fun observeLike() {
        scope.launch {
            val currentId = kotlinx.coroutines.flow.MutableStateFlow(player.currentMediaItem?.mediaId)
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentId.value = mediaItem?.mediaId
                }
            })
            combine(currentId, container.library.likedIds) { id, liked -> id != null && id in liked }
                .distinctUntilChanged()
                .collect { liked -> session.setMediaButtonPreferences(buttons(liked)) }
        }
    }

    private fun buttons(liked: Boolean): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Quitar de Me gusta" else "Me gusta")
            .setSessionCommand(LyraCommands.LIKE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun restoreQueue() {
        val saved = queueStore.load() ?: return
        if (saved.songs.isEmpty()) return
        player.setMediaItems(
            saved.songs.map { it.toMediaItem(container.downloads.localCover(it.id)) },
            saved.index.coerceIn(0, saved.songs.size - 1),
            saved.positionMs,
        )
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        player.prepare()
    }

    private fun rebuild(item: MediaItem): MediaItem {
        val song = item.toSong() ?: runBlocking {
            container.database.songs().get(MediaItems.songIdOf(item.mediaId))?.toSong()
        } ?: return item
        return song.toMediaItem(container.downloads.localCover(song.id))
    }

    private inner class ServiceListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retryCount = 0
            queueStore.scheduleSave(player)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && pendingShuffleStart && player.mediaItemCount > 0) {
                pendingShuffleStart = false
                if (player.shuffleModeEnabled) shuffleFromCurrent()
            }
            queueStore.scheduleSave(player)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Al activar el aleatorio a mitad de lista, la actual sigue y el resto se baraja.
            if (shuffleModeEnabled && !pendingShuffleStart) shuffleFromCurrent()
            queueStore.scheduleSave(player)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                updateWidget()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val context = this@PlaybackService
            when {
                playWhenReady -> HeadsetResume.clear(context)
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> HeadsetResume.markPausedByDisconnect(context)
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> HeadsetResume.clear(context)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            queueStore.scheduleSave(player, if (isPlaying) 1_500 else 0)
            if (isPlaying) {
                scope.launch {
                    // Guarda la posición de vez en cuando mientras suena.
                    while (isActive && player.isPlaying) {
                        delay(15_000)
                        queueStore.save(player)
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val songId = player.currentMediaItem?.mediaId ?: return
            val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val recoverable = httpCode == 403 || httpCode == 410 ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            if (recoverable && retryCount < 2) {
                // La URL caducó o falló la red: se pide una nueva y se sigue donde iba.
                retryCount++
                container.streamResolver.invalidate(songId)
                val position = player.currentPosition
                scope.launch {
                    delay(600L * retryCount)
                    player.seekTo(player.currentMediaItemIndex, position)
                    player.prepare()
                    player.play()
                }
            } else if (player.hasNextMediaItem()) {
                // Si una canción no se puede reproducir, se salta a la siguiente.
                retryCount = 0
                scope.launch {
                    delay(800)
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LyraCommands.LIKE)
                .add(LyraCommands.START_RADIO)
                .add(LyraCommands.PLAYLIST_RADIO)
                .add(LyraCommands.NEW_QUEUE)
                .build()
            return MediaSession.ConnectionResult.accept(commands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                LyraCommands.ACTION_LIKE -> {
                    val song = player.currentMediaItem?.toSong()
                    if (song != null) scope.launch { container.library.toggleLike(song) }
                }
                LyraCommands.ACTION_START_RADIO -> {
                    args.getString(LyraCommands.ARG_SONG)
                        ?.let { runCatching { json.decodeFromString(Song.serializer(), it) }.getOrNull() }
                        ?.let { crossfade.cancel(); radio.start(it) }
                }
                LyraCommands.ACTION_PLAYLIST_RADIO -> {
                    args.getString(LyraCommands.ARG_PLAYLIST)?.let { crossfade.cancel(); radio.startFromPlaylist(it) }
                }
                LyraCommands.ACTION_NEW_QUEUE -> {
                    crossfade.cancel()
                    radio.reset()
                    pendingShuffleStart = args.getBoolean(LyraCommands.ARG_SHUFFLE, false)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(::rebuild).toMutableList())

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            crossfade.cancel()
            radio.reset()
            val single = mediaItems.singleOrNull()
            if (single != null && single.mediaId.contains("::")) {
                // Android Auto: al tocar una canción de una lista, suena la lista entera desde ella.
                return scope.future {
                    val contextId = single.mediaId.substringBefore("::")
                    val songId = MediaItems.songIdOf(single.mediaId)
                    val songs = autoLibrary.songsFor(contextId).ifEmpty { listOfNotNull(single.toSong()) }
                    val index = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                    MediaSession.MediaItemsWithStartPosition(
                        songs.map { it.toMediaItem(container.downloads.localCover(it.id)) }, index, C.TIME_UNSET,
                    )
                }
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems.map(::rebuild), startIndex, startPositionMs),
            )
        }

        @Deprecated("Media3 mantiene esta firma por compatibilidad")
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = queueStore.load()
            if (saved == null || saved.songs.isEmpty()) {
                return Futures.immediateFailedFuture(UnsupportedOperationException("No hay nada que retomar"))
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    saved.songs.map { it.toMediaItem(container.downloads.localCover(it.id)) },
                    saved.index,
                    saved.positionMs,
                ),
            )
        }

        // ------------------------------------------------ Android Auto

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(autoLibrary.root, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = runCatching { autoLibrary.children(parentId) }.getOrDefault(emptyList())
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val songId = MediaItems.songIdOf(mediaId)
            val song = container.database.songs().get(songId)?.toSong()
            if (song != null) LibraryResult.ofItem(autoLibrary.playable(song, mediaId.substringBefore("::")), null)
            else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val results = runCatching { autoLibrary.search(query) }.getOrDefault(emptyList())
            searchResults[query] = results
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val results = searchResults[query] ?: autoLibrary.search(query)
            val items = results.map { autoLibrary.playable(it, AutoLibrary.SEARCH_PREFIX + query) }
            val from = (page * pageSize).coerceAtMost(items.size)
            val to = (from + pageSize).coerceAtMost(items.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si no está sonando nada, al cerrar la app desde recientes se para el servicio.
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        headphones.stop()
        // Al cerrarse el servicio, el widget se queda con la canción en pausa.
        val last = player.currentMediaItem
        val app = applicationContext
        container.scope.launch {
            runCatching { com.lyra.music.widget.WidgetUpdater.push(app, last?.toSong(), last?.mediaMetadata?.artworkUri, false) }
        }
        tracker.commit()
        queueStore.saveBlocking(player)
        container.island.detach()
        crossfade.release()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }
}
