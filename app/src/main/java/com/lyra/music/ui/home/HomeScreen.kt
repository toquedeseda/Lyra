package com.lyra.music.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.model.Chip
import com.lyra.music.data.model.Section
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.ChipRow
import com.lyra.music.ui.components.ErrorView
import com.lyra.music.ui.components.Eyebrow
import com.lyra.music.ui.components.InfoCard
import com.lyra.music.ui.components.LoadingView
import com.lyra.music.ui.components.PillButton
import com.lyra.music.ui.components.QuickTile
import com.lyra.music.ui.components.SectionView
import com.lyra.music.ui.components.friendlyError
import com.lyra.music.ui.navigation.BrowseRoute
import com.lyra.music.ui.navigation.HistoryRoute
import com.lyra.music.ui.navigation.PlaylistRoute
import com.lyra.music.ui.navigation.SettingsRoute
import com.lyra.music.ui.theme.LyraColors
import kotlinx.coroutines.launch
import java.util.Calendar
import com.lyra.music.ui.components.SectionsSkeleton
import androidx.activity.compose.ReportDrawnWhen

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun HomeScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val container = actions.container
    val feed by container.home.feed.collectAsState()
    val settings by container.settings.flow.collectAsState()
    val downloads by container.downloads.states.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var selectedChip by rememberSaveable { mutableIntStateOf(0) }
    var chipSections by remember { mutableStateOf<List<Section>?>(null) }
    var chipError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val online by container.network.online.collectAsState()

    // Para medir el arranque: la app está lista cuando el Inicio ya enseña algo.
    ReportDrawnWhen { !online || feed.quickAccess.isNotEmpty() || feed.sections.isNotEmpty() }

    // Al volver internet, el Inicio se pone al día.
    LaunchedEffect(online) { if (online) container.home.refresh() }
    if (!online) {
        OfflineHome(contentPadding)
        return
    }

    fun selectChip(index: Int, chip: Chip?) {
        selectedChip = index
        chipSections = null
        chipError = null
        if (chip != null) scope.launch {
            runCatching { container.home.chipSections(chip) }
                .onSuccess { chipSections = it }
                .onFailure { chipError = friendlyError(it) }
        }
    }

    fun openMore(section: Section) {
        val more = section.more ?: return
        if (more.browseId.startsWith("VL")) actions.nav.navigate(PlaylistRoute("yt:" + more.browseId))
        else actions.nav.navigate(BrowseRoute(more.browseId, more.params, section.title))
    }

    // Carga más secciones al acercarse al final.
    val nearEnd by remember {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 3 }
    }
    LaunchedEffect(nearEnd, feed.canLoadMore) {
        if (nearEnd && feed.canLoadMore && selectedChip == 0) container.home.loadMore()
    }

    val offline = downloads.values.count { it.state == com.lyra.music.data.db.DownloadState.COMPLETED }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                container.home.refresh(force = true)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(
                    Modifier
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 8.dp, top = 18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Lyra", Modifier.weight(1f))
                        IconButton(onClick = { actions.nav.navigate(HistoryRoute) }) {
                            Icon(Icons.Outlined.History, "Historial", tint = LyraColors.TextSecondary)
                        }
                        IconButton(onClick = { actions.nav.navigate(SettingsRoute) }) {
                            Icon(Icons.Outlined.Settings, "Ajustes", tint = LyraColors.TextSecondary)
                        }
                    }
                    Text(greeting(), style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(end = 12.dp))
                    Text(
                        if (offline > 0) "Música sin anuncios. Tienes $offline canciones listas para escuchar sin conexión."
                        else "Música sin anuncios de YouTube Music y SoundCloud, también sin conexión.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LyraColors.TextSecondary,
                        modifier = Modifier.padding(top = 10.dp, end = 12.dp),
                    )
                }
            }
            if (feed.chips.isNotEmpty()) {
                item {
                    ChipRow(
                        labels = listOf("Todo") + feed.chips.map { it.title },
                        selected = selectedChip,
                        onSelect = { index -> selectChip(index, feed.chips.getOrNull(index - 1)) },
                        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
                    )
                }
            }
            if (selectedChip == 0) {
                if (feed.quickAccess.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            feed.quickAccess.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { QuickTile(it, Modifier.weight(1f)) }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                if (!settings.batteryTipDismissed && !ignoresBatteryOptimizations(context)) {
                    item {
                        InfoCard(
                            title = "Que no se corte la música",
                            text = "Los Vivo cierran las apps en segundo plano. Quita la optimización de batería a Lyra para que la música y las descargas sigan con la pantalla apagada.",
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp),
                        ) {
                            PillButton("Arreglarlo", onClick = { openBatterySettings(context) }, icon = Icons.Outlined.BatteryAlert)
                            TextButton(onClick = { scope.launch { container.settings.update { it.copy(batteryTipDismissed = true) } } }) {
                                Text("Ahora no", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                itemsIndexed(feed.sections, key = { index, section -> "$index-${section.title}" }) { _, section ->
                    SectionView(section, onMore = section.more?.let { { openMore(section) } })
                }
                if (feed.sections.isEmpty()) {
                    item {
                        when {
                            feed.error != null -> ErrorView(feed.error!!, onRetry = { scope.launch { container.home.refresh(force = true) } })
                            else -> SectionsSkeleton()
                        }
                    }
                }
            } else {
                val sections = chipSections
                when {
                    chipError != null -> item { ErrorView(chipError!!, onRetry = { selectChip(selectedChip, feed.chips.getOrNull(selectedChip - 1)) }) }
                    sections == null -> item { SectionsSkeleton() }
                    else -> itemsIndexed(sections, key = { index, s -> "chip$index-${s.title}" }) { _, section ->
                        SectionView(section, onMore = section.more?.let { { openMore(section) } })
                    }
                }
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 6..13 -> "Buenos días"
    in 14..20 -> "Buenas tardes"
    else -> "Buenas noches"
}

fun ignoresBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@android.annotation.SuppressLint("BatteryLife")
fun openBatterySettings(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(request) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
