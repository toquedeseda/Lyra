package com.lyra.music.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.lyra.music.AppContainer
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.MoodGroup
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.SearchPage
import com.lyra.music.data.model.SearchSuggestions
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.SearchTab
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.components.ChipRow
import com.lyra.music.ui.components.EmptyView
import com.lyra.music.ui.components.ErrorView
import com.lyra.music.ui.components.ItemRow
import com.lyra.music.ui.components.Loadable
import com.lyra.music.ui.components.LoadingView
import com.lyra.music.ui.components.PlayCircleButton
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.artworkFor
import com.lyra.music.ui.components.friendlyError
import com.lyra.music.ui.components.subtitleOf
import com.lyra.music.ui.navigation.BrowseRoute
import com.lyra.music.ui.theme.LyraColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@UnstableApi
class SearchViewModel(private val container: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var submitted by mutableStateOf<String?>(null)
        private set
    var tab by mutableStateOf(SearchTab.ALL)
        private set

    val suggestions = MutableStateFlow<SearchSuggestions?>(null)
    val results = MutableStateFlow<Loadable<SearchPage>>(Loadable.Loading)
    val moods = MutableStateFlow<Loadable<List<MoodGroup>>>(Loadable.Loading)
    val recent = container.library.recentSearches

    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var loadingMore = false

    init {
        loadMoods()
    }

    fun loadMoods() {
        viewModelScope.launch {
            moods.value = runCatching { Loadable.Ready(container.music.moods()) }.getOrElse { Loadable.Error(friendlyError(it)) }
        }
    }

    fun onQueryChange(value: String) {
        query = value
        suggestJob?.cancel()
        if (value.isBlank() || value.startsWith("http")) {
            suggestions.value = null
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            suggestions.value = runCatching { container.music.suggestions(value) }.getOrNull()
        }
    }

    /** Devuelve true si era un enlace (lo gestiona quien llama). */
    fun submit(value: String = query): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return false
        query = text
        suggestJob?.cancel()
        suggestions.value = null
        if (text.startsWith("http")) return true
        submitted = text
        viewModelScope.launch { container.library.addSearch(text) }
        search()
        return false
    }

    fun selectTab(newTab: SearchTab) {
        if (tab == newTab) return
        tab = newTab
        search()
    }

    fun clear() {
        query = ""
        submitted = null
        suggestions.value = null
    }

    fun removeRecent(text: String) = viewModelScope.launch { container.library.removeSearch(text) }

    fun search() {
        val q = submitted ?: return
        searchJob?.cancel()
        results.value = Loadable.Loading
        searchJob = viewModelScope.launch {
            results.value = runCatching { Loadable.Ready(container.music.search(q, tab)) }
                .getOrElse { Loadable.Error(friendlyError(it)) }
        }
    }

    fun loadMore() {
        val current = (results.value as? Loadable.Ready)?.value ?: return
        val token = current.continuation ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            runCatching { container.music.searchMore(token) }.onSuccess { more ->
                results.value = Loadable.Ready(
                    current.copy(items = (current.items + more.items).distinctBy { it.id }, continuation = more.continuation),
                )
            }.onFailure {
                results.value = Loadable.Ready(current.copy(continuation = null))
            }
            loadingMore = false
        }
    }
}

@UnstableApi
@Composable
fun SearchScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val vm: SearchViewModel = viewModel { SearchViewModel(actions.container) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val suggestions by vm.suggestions.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            if (!focused && vm.submitted == null) {
                Text("Buscar", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }
            SearchField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                onSubmit = {
                    keyboard?.hide()
                    focus.clearFocus()
                    if (vm.submit()) actions.openLink(vm.query)
                },
                onClear = vm::clear,
                modifier = Modifier.onFocusChanged { focused = it.isFocused },
            )
        }
        Spacer(Modifier.height(8.dp))

        val showSuggestions = focused && vm.query.isNotBlank() && vm.query != vm.submitted
        when {
            showSuggestions -> Suggestions(
                suggestions = suggestions,
                contentPadding = contentPadding,
                onQuery = {
                    keyboard?.hide()
                    focus.clearFocus()
                    vm.submit(it)
                },
                onItem = {
                    focus.clearFocus()
                    actions.open(it)
                },
            )
            vm.submitted != null -> Results(vm, contentPadding)
            else -> Explore(vm, contentPadding, onRecent = {
                focus.clearFocus()
                vm.submit(it)
            })
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Color.Black)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("¿Qué te apetece escuchar?", color = Color(0xFF6A6A6A), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Color.Black),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Borrar", tint = Color.Black)
            }
        }
    }
}

@UnstableApi
@Composable
private fun Suggestions(
    suggestions: SearchSuggestions?,
    contentPadding: PaddingValues,
    onQuery: (String) -> Unit,
    onItem: (MusicItem) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
        items(suggestions?.queries.orEmpty(), key = { "q$it" }) { text ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onQuery(text) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = LyraColors.TextSecondary)
                Spacer(Modifier.width(16.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.CallMade, null, tint = LyraColors.TextTertiary, modifier = Modifier.size(18.dp))
            }
        }
        items(suggestions?.items.orEmpty(), key = { "i${it.id}" }) { item ->
            ItemRow(item, onClick = { onItem(item) })
        }
    }
}

@UnstableApi
@Composable
private fun Explore(vm: SearchViewModel, contentPadding: PaddingValues, onRecent: (String) -> Unit) {
    val actions = LocalActions.current
    val recent by vm.recent.collectAsState(initial = emptyList())
    val moods by vm.moods.collectAsState()
    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        if (recent.isNotEmpty()) {
            item {
                Text("Búsquedas recientes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            }
            items(recent, key = { "r$it" }) { text ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onRecent(text) }
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = LyraColors.TextSecondary)
                    Spacer(Modifier.width(16.dp))
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.removeRecent(text) }) { Icon(Icons.Rounded.Close, "Quitar", tint = LyraColors.TextTertiary) }
                }
            }
        }
        when (val state = moods) {
            is Loadable.Ready -> state.value.forEach { group ->
                item {
                    Text(group.title.ifEmpty { "Explorar" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                }
                items(group.categories.chunked(2), key = { row -> "m${group.title}${row.first().title}" }) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { index, mood ->
                            MoodTile(mood.title, shade = (group.categories.indexOf(mood) % 5), modifier = Modifier.weight(1f)) {
                                actions.nav.navigate(BrowseRoute(mood.endpoint.browseId, mood.endpoint.params, mood.title))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            is Loadable.Error -> item { ErrorView(state.message, onRetry = vm::loadMoods) }
            Loadable.Loading -> item { LoadingView(Modifier.height(200.dp)) }
        }
    }
}

@Composable
private fun MoodTile(title: String, shade: Int, modifier: Modifier, onClick: () -> Unit) {
    // Distintos grises para que la rejilla no sea plana.
    val colors = listOf(Color(0xFF3A3A3A), Color(0xFF2C2C2C), Color(0xFF454545), Color(0xFF333333), Color(0xFF262626))
    Box(
        modifier
            .height(92.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(colors[shade], Color(0xFF151515))))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

@UnstableApi
@Composable
private fun Results(vm: SearchViewModel, contentPadding: PaddingValues) {
    val results by vm.results.collectAsState()
    val listState = rememberLazyListState()
    val tabs = SearchTab.entries

    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, results) { if (nearEnd) vm.loadMore() }

    Column {
        ChipRow(tabs.map { it.label }, tabs.indexOf(vm.tab), { vm.selectTab(tabs[it]) }, Modifier.padding(bottom = 8.dp))
        when (val state = results) {
            Loadable.Loading -> LoadingView()
            is Loadable.Error -> ErrorView(state.message, onRetry = vm::search)
            is Loadable.Ready -> {
                val page = state.value
                if (page.items.isEmpty() && page.topResult == null) {
                    EmptyView(Icons.Rounded.SearchOff, "Sin resultados", "Prueba con otras palabras o mira en SoundCloud.")
                } else {
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
                        page.topResult?.let { top -> item(key = "top") { TopResult(top) } }
                        items(page.items, key = { it.id }) { item -> ItemRow(item) }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun TopResult(item: MusicItem) {
    val actions = LocalActions.current
    Column(Modifier.padding(16.dp)) {
        Text("Resultado principal", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LyraColors.SurfaceHigh)
                .clickable { actions.open(item) }
                .padding(16.dp),
        ) {
            Column {
                val model = if (item is Song) artworkFor(item) else item.thumbnailUrl
                Artwork(model, Modifier.size(96.dp), if (item is ArtistItem) CircleShape else RoundedCornerShape(6.dp))
                Spacer(Modifier.height(14.dp))
                Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitleOf(item), style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, maxLines = 1)
            }
            if (item is Song || item is AlbumItem || item is ArtistItem) {
                PlayCircleButton(
                    onClick = {
                        when (item) {
                            is Song -> actions.startRadio(item)
                            else -> actions.open(item)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd),
                    size = 48.dp,
                )
            }
        }
    }
}
