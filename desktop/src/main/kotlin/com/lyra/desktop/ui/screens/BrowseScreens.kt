package com.lyra.desktop.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.components.LoadingView
import com.lyra.desktop.ui.components.SongRow
import com.lyra.music.data.model.BrowseEndpoint
import com.lyra.music.data.model.Section
import com.lyra.music.data.model.Song

/** Una página de YouTube Music (un género, «Mostrar todo» de una sección…). */
@Composable
fun BrowseScreen(endpoint: BrowseEndpoint, title: String?) {
    val app = LocalActions.current.app
    val (load, retry) = rememberLoad("browse:${endpoint.browseId}:${endpoint.params}") { app.music.browse(endpoint) }
    ScreenList {
        item {
            Text(
                (load as? Load.Ready)?.value?.title ?: title ?: "",
                style = MaterialTheme.typography.displayMedium,
                color = LyraColors.TextPrimary,
                modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 8.dp),
            )
        }
        when (load) {
            is Load.Loading -> item { LoadingView() }
            is Load.Failed -> item { ErrorView(load.message, retry) }
            is Load.Ready -> {
                val sections = load.value.sections
                // Si solo hay una sección, entera en rejilla; si hay varias, como en el Inicio.
                if (sections.size == 1) {
                    item { AllOfSection(sections.first()) }
                } else {
                    items(sections, key = { "b-" + it.title }) { SectionView(it) }
                }
            }
        }
    }
}

/** «Mostrar todo» de una sección que ya tenemos entera. */
@Composable
fun AllOfSectionScreen(section: Section) {
    ScreenList {
        item {
            Text(
                section.title,
                style = MaterialTheme.typography.displayMedium,
                color = LyraColors.TextPrimary,
                modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 8.dp),
            )
        }
        item { AllOfSection(section) }
    }
}

@Composable
private fun AllOfSection(section: Section) {
    val actions = LocalActions.current
    val songs = section.items.filterIsInstance<Song>()
    Column(Modifier.padding(horizontal = PagePadding - 10.dp)) {
        if (songs.size == section.items.size && songs.isNotEmpty()) {
            songs.forEachIndexed { index, song ->
                SongRow(song, onPlay = { actions.play(songs, index) }, index = index + 1, showAlbum = true)
            }
        } else {
            CardsGrid(section.items)
        }
    }
}
