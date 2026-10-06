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
import com.lyra.music.data.model.cleaned
import com.lyra.music.data.settings.AppSettings
import com.lyra.music.playback.MediaItems.isRadio
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Órdenes propias que la interfaz y la notificación envían al servicio. */
object LyraCommands {
    const val ACTION_LIKE = "lyra.like"
    const val ACTION_START_RADIO = "lyra.radio"
    const val ACTION_PLAYLIST_RADIO = "lyra.playlist_radio"
    const val ACTION_NEW_QUEUE = "lyra.new_queue"
    const val ACTION_SHUFFLE = "lyra.shuffle"
    const val ACTION_REPEAT = "lyra.repeat"
    const val ACTION_RADIO_HERE = "lyra.radio_here"
    const val ARG_SONG = "song"
    const val ARG_PLAYLIST = "playlist"
    const val ARG_SHUFFLE = "shuffle"
    const val ARG_FROM = "from"
    const val ARG_CONTEXT = "context"

    /** En los extras de la sesión: de dónde sale lo que suena (texto e id). */
    const val EXTRA_CONTEXT_LABEL = "lyra.context_label"
    const val EXTRA_CONTEXT_ID = "lyra.context_id"

    val LIKE = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    val START_RADIO = SessionCommand(ACTION_START_RADIO, Bundle.EMPTY)
    val PLAYLIST_RADIO = SessionCommand(ACTION_PLAYLIST_RADIO, Bundle.EMPTY)
    val NEW_QUEUE = SessionCommand(ACTION_NEW_QUEUE, Bundle.EMPTY)
    val SHUFFLE = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
    val REPEAT = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)
    val RADIO_HERE = SessionCommand(ACTION_RADIO_HERE, Bundle.EMPTY)
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
    private val searchResults = mutableMapOf<String, List<MediaItem>>()
    private var retryCount = 0
    private var pendingShuffleStart = false
    private lateinit var headphones: HeadphonesWatcher
    private lateinit var smartShuffle: SmartShuffleController
    private var widgetJob: kotlinx.coroutines.Job? = null
    private var contextLabel: String? = null
    private var contextId: String? = null
    private val shuffleState = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val repeatState = kotlinx.coroutines.flow.MutableStateFlow(Player.REPEAT_MODE_OFF)

    /**
     * En aleatorio, la canción actual pasa a ser la primera del orden y el resto se
     * baraja detrás. Así suenan TODAS las de la lista (antes, las que quedaban
     * "antes" de la actual en el orden aleatorio no llegaban a sonar).
     *
     * Las que añadió la radio al acabarse la lista se quedan al final y en su orden:
     * antes se barajaban con la lista y sonaban canciones que no eran de la playlist.
     */
    private fun shuffleFromCurrent() {
        val count = player.mediaItemCount
        if (count < 2) return
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val others = (0 until count).filter { it != current }
        val order = if (player.getMediaItemAt(current).isRadio()) {
            // Ya suena la radio (la lista se acabó): se baraja lo que queda.
            others.shuffled()
        } else {
            val (radio, own) = others.partition { player.getMediaItemAt(it).isRadio() }
            own.shuffled() + radio
        }
        player.setShuffleOrder(DefaultShuffleOrder((listOf(current) + order).toIntArray(), System.nanoTime()))
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
            loudness = { processor.loudness },
        )
        radio = RadioController(player, c.music, c.settings, c.downloads, c.library, scope)
        smartShuffle = SmartShuffleController(player, c.recommender, c.downloads, scope)
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
            .setMediaButtonPreferences(buttons(liked = false, shuffle = player.shuffleModeEnabled, repeat = player.repeatMode))
            .build()
        // Lo que sonaba al cerrar sigue diciendo de dónde venía.
        setContext(contextLabel, contextId)

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

    /** De dónde sale lo que suena ("Me gusta", una playlist, "Radio de…"): lo ven la app, el coche y el widget. */
    private fun setContext(label: String?, id: String?) {
        contextLabel = label
        contextId = id
        queueStore.contextLabel = label
        queueStore.contextId = id
        if (::session.isInitialized) {
            session.setSessionExtras(
                Bundle().apply {
                    putString(LyraCommands.EXTRA_CONTEXT_LABEL, label)
                    putString(LyraCommands.EXTRA_CONTEXT_ID, id)
                },
            )
        }
        queueStore.scheduleSave(player)
    }

    /** Una cola nueva empieza siempre sin el aleatorio inteligente (solo se activa a mano mientras suena). */
    private fun resetSmartShuffle() {
        if (!smartShuffle.enabled && !container.settings.current.smartShuffle) return
        smartShuffle.enabled = false
        scope.launch { container.settings.update { it.copy(smartShuffle = false) } }
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
                crossfade.smart = settings.smartCrossfade
                smartShuffle.enabled = settings.smartShuffle
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
            combine(currentId, container.library.likedIds, shuffleState, repeatState) { id, liked, shuffle, repeat ->
                Triple(id != null && id in liked, shuffle, repeat)
            }
                .distinctUntilChanged()
                .collect { (liked, shuffle, repeat) -> session.setMediaButtonPreferences(buttons(liked, shuffle, repeat)) }
        }
    }

    /** Botones extra (notificación, pantalla de bloqueo y Android Auto), como en Spotify. */
    private fun buttons(liked: Boolean, shuffle: Boolean, repeat: Int): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Quitar de Me gusta" else "Me gusta")
            .setSessionCommand(LyraCommands.LIKE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (shuffle) "Quitar aleatorio" else "Aleatorio")
            .setSessionCommand(LyraCommands.SHUFFLE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(
            when (repeat) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName(
                when (repeat) {
                    Player.REPEAT_MODE_ONE -> "Repetir esta"
                    Player.REPEAT_MODE_ALL -> "Repetir todo"
                    else -> "Repetir"
                },
            )
            .setSessionCommand(LyraCommands.REPEAT)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_RADIO)
            .setDisplayName("Radio de esta canción")
            .setSessionCommand(LyraCommands.RADIO_HERE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun restoreQueue() {
        val saved = queueStore.load() ?: return
        if (saved.songs.isEmpty()) return
        player.setMediaItems(
            saved.songs.map {
                it.cleaned().toMediaItem(container.downloads.localCover(it.id), recommended = it.id in saved.recommended, radio = it.id in saved.radio)
            },
            saved.index.coerceIn(0, saved.songs.size - 1),
            saved.positionMs,
        )
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        shuffleState.value = saved.shuffle
        repeatState.value = saved.repeatMode
        contextLabel = saved.contextLabel
        contextId = saved.contextId
        player.prepare()
    }

    private fun rebuild(item: MediaItem): MediaItem {
        val song = item.toSong() ?: runBlocking {
            container.database.songs().get(MediaItems.songIdOf(item.mediaId))?.toSong()
        } ?: return item
        return song.toMediaItem(container.downloads.localCover(song.id))
    }

    /** "Ok Google, pon música en Lyra" sin decir qué, con algo ya en la cola: sigue con ella tal cual. */
    private fun voiceResume(query: String): MediaSession.MediaItemsWithStartPosition? {
        if (query.isNotBlank() || player.mediaItemCount == 0) return null
        pendingShuffleStart = true
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        return MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
    }

    /**
     * "Ok Google, pon … en Lyra". Si no se encuentra (o no hay internet), en el coche es
     * mejor que suene algo tuyo que nada: tus Me gusta o, sin conexión, tus descargas.
     */
    private suspend fun voicePick(query: String, extras: Bundle?): AutoLibrary.Pick? {
        if (query.isNotBlank()) {
            autoLibrary.voice(query, extras)?.let { return it }
            com.lyra.music.core.ErrorLog.record("Android Auto", "No encontré «$query» por voz; suenan tus canciones")
        }
        val liked = if (container.network.isOnline) autoLibrary.pick(AutoLibrary.SHUFFLE + AutoLibrary.LIKED) else null
        return liked ?: autoLibrary.pick(AutoLibrary.SHUFFLE + AutoLibrary.DOWNLOADS)
    }

    /** Pone en marcha lo elegido en el coche: lista, punto de inicio, aleatorio y "Reproduciendo desde". */
    private fun startFromCar(pick: AutoLibrary.Pick): MediaSession.MediaItemsWithStartPosition {
        resetSmartShuffle()
        pendingShuffleStart = true
        pick.shuffle?.let { player.shuffleModeEnabled = it }
        setContext(pick.label, pick.contextId)
        pick.note?.let(container.library::noteContext)
        val songs = pick.songs.map { it.cleaned() }
        val target = songs.getOrNull(pick.index)
        // Sin internet solo puede sonar lo descargado (si no hay nada descargado, se intenta igual).
        val offline = !container.network.isOnline
        val available = if (offline) songs.filter { container.downloads.isDownloaded(it.id) }.ifEmpty { songs } else songs
        val index = available.indexOf(target).takeIf { it >= 0 } ?: if (pick.shuffle == true) available.indices.random() else 0
        return MediaSession.MediaItemsWithStartPosition(
            available.map { it.toMediaItem(container.downloads.localCover(it.id)) },
            index,
            if (available.size == songs.size) pick.positionMs else C.TIME_UNSET,
        )
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

        override fun onRepeatModeChanged(repeatMode: Int) {
            repeatState.value = repeatMode
            queueStore.scheduleSave(player)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffleState.value = shuffleModeEnabled
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
            // Sin internet, lo que no está descargado no puede sonar: se salta sin reintentar.
            if (!container.network.isOnline && !container.downloads.isDownloaded(songId)) {
                retryCount = 0
                if (player.hasNextMediaItem()) {
                    scope.launch {
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }
                return
            }
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
            } else {
                // Ya no hay más intentos: se apunta en el informe de errores.
                val song = player.currentMediaItem?.toSong()
                com.lyra.music.core.ErrorLog.record(
                    "Reproducción",
                    "${song?.title ?: songId}: ${error.cause?.message ?: error.errorCodeName}",
                    error,
                    extra = "$songId · ${error.errorCodeName}",
                )
                if (player.hasNextMediaItem()) {
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
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LyraCommands.LIKE)
                .add(LyraCommands.START_RADIO)
                .add(LyraCommands.PLAYLIST_RADIO)
                .add(LyraCommands.NEW_QUEUE)
                .add(LyraCommands.SHUFFLE)
                .add(LyraCommands.REPEAT)
                .add(LyraCommands.RADIO_HERE)
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
                        ?.let { song ->
                            crossfade.cancel()
                            resetSmartShuffle()
                            radio.start(song)
                            setContext(args.getString(LyraCommands.ARG_FROM) ?: "Radio de ${song.title}", "radio:${song.id}")
                        }
                }
                LyraCommands.ACTION_PLAYLIST_RADIO -> {
                    args.getString(LyraCommands.ARG_PLAYLIST)?.let { playlistId ->
                        crossfade.cancel()
                        resetSmartShuffle()
                        radio.startFromPlaylist(playlistId)
                        setContext(args.getString(LyraCommands.ARG_FROM), "radio:$playlistId")
                    }
                }
                LyraCommands.ACTION_NEW_QUEUE -> {
                    crossfade.cancel()
                    radio.reset()
                    resetSmartShuffle()
                    pendingShuffleStart = args.getBoolean(LyraCommands.ARG_SHUFFLE, false)
                    setContext(args.getString(LyraCommands.ARG_FROM), args.getString(LyraCommands.ARG_CONTEXT))
                }
                LyraCommands.ACTION_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                LyraCommands.ACTION_REPEAT -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                LyraCommands.ACTION_RADIO_HERE -> player.currentMediaItem?.toSong()?.let { song ->
                    crossfade.cancel()
                    resetSmartShuffle()
                    radio.start(song)
                    setContext("Radio de ${song.title}", "radio:${song.id}")
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
            val voiceQuery = single?.requestMetadata?.searchQuery
            if (single != null && (voiceQuery != null || autoLibrary.isAutoId(single.mediaId))) {
                // Android Auto: al tocar algo suena su lista entera; por voz, lo que mejor encaje.
                return scope.future {
                    if (voiceQuery != null) {
                        voiceResume(voiceQuery)?.let { return@future it }
                    }
                    val pick = runCatching {
                        if (voiceQuery != null) voicePick(voiceQuery, single.requestMetadata.extras) else autoLibrary.pick(single.mediaId)
                    }.onFailure {
                        com.lyra.music.core.ErrorLog.record("Android Auto", it.message ?: "No se pudo poner", it, extra = single.mediaId.ifBlank { voiceQuery })
                    }.getOrNull() ?: throw IllegalStateException("Nada que poner para «${voiceQuery ?: single.mediaId}»")
                    startFromCar(pick)
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
            pendingShuffleStart = saved.shuffle
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            setContext(saved.contextLabel, saved.contextId)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    saved.songs.map {
                        it.cleaned().toMediaItem(container.downloads.localCover(it.id), recommended = it.id in saved.recommended, radio = it.id in saved.radio)
                    },
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
            Futures.immediateFuture(
                LibraryResult.ofItem(autoLibrary.root, LibraryParams.Builder().setExtras(autoLibrary.rootExtras).build()),
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = withContext(Dispatchers.Default) { runCatching { autoLibrary.children(parentId) }.getOrDefault(emptyList()) }
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            autoLibrary.item(mediaId)?.let { return@future LibraryResult.ofItem(it, null) }
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
            val results = withContext(Dispatchers.Default) { runCatching { autoLibrary.search(query) }.getOrDefault(emptyList()) }
            if (searchResults.size > 20) searchResults.clear()
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
            val items = searchResults[query]
                ?: withContext(Dispatchers.Default) { runCatching { autoLibrary.search(query) }.getOrDefault(emptyList()) }
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
        smartShuffle.release()
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
