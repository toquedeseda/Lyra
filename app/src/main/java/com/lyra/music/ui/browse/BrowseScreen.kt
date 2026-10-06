package com.lyra.music.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.lyra.music.AppContainer
import com.lyra.music.data.model.BrowseEndpoint
import com.lyra.music.data.model.BrowsePage
import com.lyra.music.data.model.SectionStyle
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.BackBar
import com.lyra.music.ui.components.ErrorView
import com.lyra.music.ui.components.ItemCard
import com.lyra.music.ui.components.LoadViewModel
import com.lyra.music.ui.components.Loadable
import com.lyra.music.ui.components.LoadingView
import com.lyra.music.ui.components.SectionView
import com.lyra.music.ui.navigation.BrowseRoute
import com.lyra.music.ui.navigation.PlaylistRoute
import com.lyra.music.ui.playlist.RemotePlaylistScreen
import com.lyra.music.ui.components.SectionsSkeleton

@UnstableApi
class BrowseViewModel(private val container: AppContainer, private val endpoint: BrowseEndpoint) : LoadViewModel<BrowsePage>() {
    override suspend fun fetch(): BrowsePage = container.music.browse(endpoint)
    init { load() }
}

/** Página genérica de YouTube Music: categorías, "Mostrar todo", discografías… */
@UnstableApi
@Composable
fun BrowseScreen(browseId: String, params: String?, title: String?, contentPadding: PaddingValues) {
    if (browseId.startsWith("VL")) {
        RemotePlaylistScreen("yt:$browseId", contentPadding)
        return
    }
    val actions = LocalActions.current
    val vm: BrowseViewModel = viewModel(key = "$browseId$params") { BrowseViewModel(actions.container, BrowseEndpoint(browseId, params)) }
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        when (val s = state) {
            Loadable.Loading -> { BackBar(title); SectionsSkeleton(sections = 3) }
            is Loadable.Error -> { BackBar(title); ErrorView(s.message, onRetry = vm::load) }
            is Loadable.Ready -> {
                val page = s.value
                BackBar(title ?: page.title)
                val single = page.sections.singleOrNull()
                if (single != null && single.style == SectionStyle.CAROUSEL) {
                    // Una sola sección (p. ej. discografía): mejor en rejilla.
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(single.items, key = { it.id }) { item -> ItemCard(item, width = 170.dp) }
                        item(span = { GridItemSpan(maxLineSpan) }) { }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
                        page.sections.forEachIndexed { index, section ->
                            item(key = "$index${section.title}") {
                                SectionView(
                                    section,
                                    onMore = section.more?.let { more ->
                                        {
                                            if (more.browseId.startsWith("VL")) actions.nav.navigate(PlaylistRoute("yt:" + more.browseId))
                                            else actions.nav.navigate(BrowseRoute(more.browseId, more.params, section.title))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
