package com.lyra.music.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object SearchRoute
@Serializable data object LibraryRoute

@Serializable data class AlbumRoute(val id: String)
@Serializable data class ArtistRoute(val id: String)
@Serializable data class PlaylistRoute(val id: String)
@Serializable data class LocalPlaylistRoute(val id: Long)
@Serializable data class BrowseRoute(val browseId: String, val params: String? = null, val title: String? = null)

@Serializable data object LikedRoute
@Serializable data object DownloadsRoute
@Serializable data object HistoryRoute

@Serializable data object SettingsRoute
@Serializable data object EqualizerRoute
@Serializable data object IslandRoute
