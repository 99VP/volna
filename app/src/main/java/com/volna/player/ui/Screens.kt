@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.volna.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.imageLoader
import com.volna.player.R
import com.volna.player.BuildConfig
import com.volna.player.data.GameStore
import com.volna.player.data.LibraryStore
import com.volna.player.data.Playlist
import com.volna.player.data.SettingsStore
import com.volna.player.data.Track
import com.volna.player.net.BypassState
import com.volna.player.net.ProxyRuntime
import com.volna.player.player.PlayerViewModel
import com.volna.player.player.QueueEntry
import com.volna.player.player.TrendFolder
import com.volna.player.ui.theme.VolnaPalette
import com.volna.player.ui.theme.VolnaPalettes
import com.volna.player.ui.theme.ThemeReveal
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================ ПОИСК (окошко сверху) ============================

@Composable
fun SearchOverlay(vm: PlayerViewModel, onDismiss: () -> Unit, onPlayed: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()
    val error by vm.searchError.collectAsState()

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        val piratActive = vm.pirat.value
        if (q.startsWith("http")) {
            vm.playUrl(q)
            onPlayed()
        } else {
            vm.search(q, -1)
        }
        // в режиме пасхалки очищаем поле, чтобы удобно было вводить следующий шаг
        if (piratActive) query = ""
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(12.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(28.dp))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(28.dp),
                                    )
                                    .padding(horizontal = 18.dp, vertical = 13.dp),
                            ) {
                                if (query.isEmpty()) {
                                    Text(
                                        "Найти",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    IconButton(onClick = { doSearch() }) {
                        Icon(
                            painterResource(R.drawable.ic_search),
                            contentDescription = "Найти",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                AnimatedVisibility(
                    visible = results.isNotEmpty(),
                    enter = fadeIn(tween(240)),
                    exit = fadeOut(tween(120)),
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                            itemsIndexed(results) { index, track ->
                                Column(Modifier.animateItem()) {
                                    TrackRow(track) {
                                        vm.playFromSearch(results, index)
                                        onPlayed()
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
}

// ============================ ТЕЛЕФОН (музыка + плейлисты) ============================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PhoneScreen(vm: PlayerViewModel) {
    var sub by remember { mutableIntStateOf(0) }
    val loadingLocal by vm.loadingLocal.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(loadingLocal) { if (!loadingLocal) refreshing = false }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            FilterChip(selected = sub == 0, onClick = { sub = 0 }, label = { Text("Музыка") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = sub == 1, onClick = { sub = 1 }, label = { Text("Плейлисты") })
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                vm.reloadLibrary()
                vm.loadLocal()
            },
            modifier = Modifier.weight(1f),
        ) {
            if (sub == 0) LibraryScreen(vm) else PlaylistsScreen(vm)
        }
    }
}

// ============================ ТЕЛЕФОН ============================

@Composable
fun LibraryScreen(vm: PlayerViewModel) {
    val context = LocalContext.current
    val tracks by vm.localTracks.collectAsState()
    val loading by vm.loadingLocal.collectAsState()

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (ok) vm.loadLocal()
    }
    LaunchedEffect(granted) { if (granted) vm.loadLocal() }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_library),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text("Доступ к музыке на телефоне", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Приложение просканирует аудиофайлы в памяти устройства и покажет их здесь.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { launcher.launch(permission) }) { Text("Разрешить доступ") }
        }
        return
    }

    if (loading && tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("На телефоне не найдено музыки", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        tracks
    } else {
        tracks.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }
    }

    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) vm.loadLocal()
    }
    fun deleteLocal(track: Track) {
        val uri = track.localUri?.let { android.net.Uri.parse(it) } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = android.provider.MediaStore.createDeleteRequest(
                    context.contentResolver,
                    listOf(uri),
                )
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build(),
                )
            } else {
                context.contentResolver.delete(uri, null, null)
                vm.loadLocal()
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Поиск в музыке телефона...") },
                singleLine = true,
            )
        }
        item {
            Text(
                "Музыка на телефоне · ${filtered.size}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        itemsIndexed(filtered) { index, track ->
            TrackRow(
                track = track,
                onLongClick = { if (track.localUri != null) menuTrack = track },
            ) { vm.playTracks(filtered, index) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }

    menuTrack?.let { t ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuTrack = null },
            title = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(t.subtitle, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    deleteLocal(t)
                    menuTrack = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { menuTrack = null }) { Text("Отмена") } },
        )
    }
}

// ============================ ТРЕНДЫ ============================

@Composable
fun TrendingScreen(vm: PlayerViewModel) {
    val folders = vm.trendFolders
    val tracksMap by vm.trendTracks.collectAsState()
    val loading by vm.trendLoading.collectAsState()
    var selected by remember { mutableStateOf<TrendFolder?>(null) }

    BackHandler(enabled = selected != null) { selected = null }

    LaunchedEffect(Unit) { folders.forEach { vm.loadTrend(it.id) } }

    val open = selected
    if (open != null) {
        val list = tracksMap[open.id].orEmpty()
        val isLoading = open.id in loading
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selected = null }) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                }
                Text(open.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.loadTrend(open.id) }) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Обновить")
                }
            }
            when {
                isLoading && list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Не удалось загрузить", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        TextButton(onClick = { vm.playTracks(list, 0) }) { Text("Играть все") }
                    }
                    itemsIndexed(list) { index, track ->
                        TrackRow(track) { vm.playTracks(list, index) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Тренды", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(folders, key = { it.id }) { folder ->
            val list = tracksMap[folder.id].orEmpty()
            val isLoading = folder.id in loading
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { selected = folder }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_trending),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(folder.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (isLoading) "Загрузка..." else "${folder.subtitle} · ${list.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.loadTrend(folder.id) }) {
                    Icon(
                        painterResource(R.drawable.ic_refresh),
                        contentDescription = "Обновить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

// ============================ ПЛЕЙЛИСТЫ ============================

@Composable
fun PlaylistsScreen(vm: PlayerViewModel) {
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val history by vm.history.collectAsState()
    var selected by remember { mutableStateOf<Playlist?>(null) }
    var newName by remember { mutableStateOf("") }
    var favExpanded by remember { mutableStateOf(false) }
    var histExpanded by remember { mutableStateOf(false) }

    BackHandler(enabled = selected != null) { selected = null }

    val open = selected
    if (open != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { selected = null }) { Text("← Назад") }
                Text(
                    open.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                Button(onClick = { vm.playPlaylist(open) }, enabled = open.tracks.isNotEmpty()) {
                    Text("Играть")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    vm.deletePlaylist(open.id)
                    selected = null
                }) { Text("Удалить") }
            }
            if (open.tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Пусто. Добавляйте треки кнопкой «+» на экране плеера.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(open.tracks) { index, track ->
                        TrackRow(
                            track = track,
                            onRemove = { vm.removeFromPlaylist(open.id, track) },
                        ) { vm.playTracks(open.tracks, index) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Новый плейлист...") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (newName.isNotBlank()) {
                        vm.createPlaylist(newName)
                        newName = ""
                    }
                }) { Text("Создать") }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (favorites.isNotEmpty()) vm.playTracks(favorites, 0) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_favorite),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text("Избранное · ${favorites.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (favorites.isNotEmpty()) {
                    TextButton(onClick = { vm.playTracks(favorites, 0) }) { Text("Играть") }
                }
                if (favorites.size > 2) {
                    IconButton(onClick = { favExpanded = !favExpanded }) {
                        Text(
                            if (favExpanded) "▲" else "▼",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val favPrefix = favorites.take(2)
        itemsIndexed(favPrefix) { index, track ->
            TrackRow(
                track = track,
                onRemove = { vm.toggleFavorite(track) },
            ) { vm.playTracks(favorites, index) }
        }
        item {
            AnimatedVisibility(
                visible = favExpanded,
                enter = expandVertically(tween(260), expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(tween(260), shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column {
                    favorites.drop(2).forEachIndexed { i, track ->
                        TrackRow(
                            track = track,
                            onRemove = { vm.toggleFavorite(track) },
                        ) { vm.playTracks(favorites, i + 2) }
                    }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) }
        }
        if (history.isNotEmpty()) {
            val hist = history.take(60)
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("История", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearHistory() }) { Text("Очистить") }
                    if (hist.size > 2) {
                        IconButton(onClick = { histExpanded = !histExpanded }) {
                            Text(
                                if (histExpanded) "▲" else "▼",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            val histPrefix = hist.take(2)
            itemsIndexed(histPrefix) { index, track ->
                TrackRow(track) { vm.playTracks(hist, index) }
            }
            item {
                CurtainReveal(
                    expanded = histExpanded,
                    count = (hist.size - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                ) { i ->
                    val track = hist[i + 2]
                    TrackRow(track) { vm.playTracks(hist, i + 2) }
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) }
        }
        item {
            Text("Плейлисты", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(playlists) { playlist ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { selected = playlist }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_playlist),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${playlist.tracks.size} треков",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ============================ МОЯ ВОЛНА ============================

@Composable
fun WaveScreen(vm: PlayerViewModel) {
    val tracks by vm.wave.collectAsState()
    val loading by vm.loadingWave.collectAsState()
    val local by vm.waveLocal.collectAsState()

    LaunchedEffect(Unit) { if (tracks.isEmpty()) vm.loadWave() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Моя волна", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.loadWave() }) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Обновить")
            }
        }
        when {
            loading && tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "Послушай и полайкай треки - соберу волну под тебя. " +
                        "Без сети волна собирается из музыки на телефоне.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (local) {
                    item {
                        Text(
                            "Офлайн: собрано из музыки на телефоне",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                item {
                    TextButton(onClick = { vm.playTracks(tracks, 0) }) { Text("Играть") }
                }
                itemsIndexed(tracks) { index, track ->
                    TrackRow(track) { vm.playTracks(tracks, index) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

// ============================ ПЛЕЕР ============================

@Composable
fun PlayerScreen(vm: PlayerViewModel) {
    val now by vm.now.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val position by vm.position.collectAsState()
    val duration by vm.duration.collectAsState()
    val queue by vm.queue.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val shuffle by vm.shuffle.collectAsState()
    val repeat by vm.repeat.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val artworkScale by SettingsStore.artworkScale.collectAsState()
    val context = LocalContext.current
    val downloadProgress by vm.downloadProgress.collectAsState()
    val playerError by vm.playerError.collectAsState()

    val current = now?.let { vm.currentTrack() }
    val isFav = current != null && favorites.any { LibraryStore.sameTrack(it, current) }
    var addTrack by remember { mutableStateOf<Track?>(null) }

    var collapsed by remember { mutableStateOf(false) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val swipe = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onVerticalDrag = { _, dy -> dragAccum += dy },
            onDragEnd = {
                if (dragAccum < -30f) collapsed = true
                else if (dragAccum > 30f) collapsed = false
                dragAccum = 0f
            },
            onDragCancel = { dragAccum = 0f },
        )
    }

    val listState = rememberLazyListState()
    val nested = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Свайп вверх, когда очередь уже внизу -> свернуть
            if (available.y < 0f && !collapsed && !listState.canScrollForward) {
                collapsed = true
                return Offset(0f, available.y)
            }
            // Свайп вниз, когда очередь вверху -> развернуть
            if (available.y > 0f && collapsed && !listState.canScrollBackward) {
                collapsed = false
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(swipe)
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        }

        playerError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                textAlign = TextAlign.Center,
            )
        }

        downloadProgress?.let { dp ->
            LinearProgressIndicator(
                progress = { dp },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(animationSpec = tween(420)) + fadeIn(animationSpec = tween(420)),
            exit = shrinkVertically(animationSpec = tween(420)) + fadeOut(animationSpec = tween(420)),
        ) {
            Column(
                Modifier.then(swipe).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val artFraction = (0.5f * artworkScale).coerceIn(0.3f, 0.75f)
                val isAd = current?.id?.startsWith("pirat_ad") == true
                if (isAd) {
                    Box(
                        Modifier
                            .fillMaxWidth(artFraction)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🍵", fontSize = 80.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Николай Басков", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "золотой чай",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    VolnaArtwork(
                        model = now?.artworkUri,
                        isLocal = current?.isLocal == true,
                        modifier = Modifier
                            .fillMaxWidth(artFraction)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp)),
                    )
                }
                Spacer(Modifier.height(10.dp))
                MarqueeTitle(
                    text = now?.title ?: "Ничего не играет",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!now?.artist.isNullOrBlank()) {
                    Text(
                        now?.artist.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { current?.let { vm.toggleFavorite(it) } }, enabled = current != null) {
                        Icon(
                            painterResource(if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                            contentDescription = "В избранное",
                            modifier = Modifier.size(22.dp),
                            tint = if (isFav) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { current?.let { vm.toggleDislike(it) } }, enabled = current != null) {
                        val dis = current != null && vm.isDisliked(current)
                        Icon(
                            painterResource(R.drawable.ic_dislike),
                            contentDescription = "Не нравится",
                            modifier = Modifier.size(22.dp),
                            tint = if (dis) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    TextButton(onClick = { vm.addSimilarNow() }, enabled = current != null) {
                        Text("Похожие", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(onClick = { addTrack = current }, enabled = current != null) {
                        Icon(
                            painterResource(R.drawable.ic_queue_add),
                            contentDescription = "В плейлист",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.downloadCurrent() }, enabled = current != null) {
                        Icon(
                            painterResource(R.drawable.ic_download),
                            contentDescription = "Скачать",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            val url = current?.pageUrl
                            if (!url.isNullOrBlank()) {
                                runCatching {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, url)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Поделиться"))
                                }
                            }
                        },
                        enabled = current?.pageUrl != null,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_share),
                            contentDescription = "Поделиться",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                WaveSeekBar(position, duration, { vm.seekTo(it) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(position), style = MaterialTheme.typography.labelSmall)
                    Text(fmt(duration), style = MaterialTheme.typography.labelSmall)
                }
                PlayerControls(vm, isPlaying, shuffle, repeat, compact = false)
            }
        }

        AnimatedVisibility(
            visible = collapsed,
            enter = expandVertically(animationSpec = tween(420)) + fadeIn(animationSpec = tween(420)),
            exit = shrinkVertically(animationSpec = tween(420)) + fadeOut(animationSpec = tween(420)),
        ) {
            Column(Modifier.fillMaxWidth().then(swipe)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = now?.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            now?.title ?: "Ничего не играет",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${fmt(position)} / ${fmt(duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                WaveSeekBar(position, duration, { vm.seekTo(it) }, compact = true)
                PlayerControls(vm, isPlaying, shuffle, repeat, compact = true)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Очередь",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        LazyColumn(Modifier.fillMaxSize().nestedScroll(nested), state = listState) {
            itemsIndexed(queue) { index, entry ->
                QueueRow(
                    index = index,
                    isCurrent = index == currentIndex,
                    entry = entry,
                    onPlay = { vm.playQueueIndex(index) },
                    onAddToPlaylist = { addTrack = vm.queueTrackAt(index) },
                    onRemove = { vm.removeFromQueue(index) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }

    addTrack?.let { track ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addTrack = null },
            onCreate = { name -> vm.createPlaylist(name) },
            onPick = { id ->
                vm.addToPlaylist(id, track)
                addTrack = null
            },
        )
    }
}

@Composable
private fun PlayerControls(
    vm: PlayerViewModel,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    compact: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            IconButton(onClick = { vm.toggleShuffle() }) {
                Icon(
                    painterResource(R.drawable.ic_shuffle),
                    contentDescription = "Перемешать",
                    tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { vm.previous() }) {
            Icon(painterResource(R.drawable.ic_prev), "Предыдущий", modifier = Modifier.size(34.dp))
        }
        FilledIconButton(
            onClick = { vm.togglePlayPause() },
            modifier = Modifier.size(if (compact) 46.dp else 56.dp),
        ) {
            Icon(
                painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = "Играть/пауза",
                modifier = Modifier.size(if (compact) 26.dp else 30.dp),
            )
        }
        IconButton(onClick = { vm.next() }) {
            Icon(painterResource(R.drawable.ic_next), "Следующий", modifier = Modifier.size(34.dp))
        }
        if (!compact) {
            IconButton(onClick = { vm.cycleRepeat() }) {
                Icon(
                    painterResource(if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                    contentDescription = "Повтор",
                    tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    isCurrent: Boolean,
    entry: QueueEntry,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (isCurrent) "▶" else "${index + 1}",
            modifier = Modifier.width(22.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VolnaArtwork(
            model = entry.artworkUri,
            isLocal = entry.isLocal,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (entry.artist.isNotBlank()) {
                Text(
                    entry.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                painterResource(R.drawable.ic_queue_add),
                contentDescription = "В плейлист",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = "Убрать из очереди",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Полоса прогресса: прошедшее - длинная анимированная волна, оставшееся - прямая. */
@Composable
private fun WaveSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "phase",
    )

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(0L)
    val progress = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragFraction else progress

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 20.dp else 26.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) onSeek((offset.x / size.width * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging = false
                        if (duration > 0) onSeek((dragFraction * duration).toLong())
                    },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        val cy = size.height / 2f
        val playedX = size.width * shown
        val stroke = if (compact) 3f else 4f

        if (playedX > 1f) {
            val amp = size.height * 0.2f
            val wavelength = 120f
            val fade = 40f
            val path = Path()
            var x = 0f
            path.moveTo(0f, cy)
            while (x <= playedX) {
                // гасим амплитуду у обоих краёв, чтобы вход и выход были плавными
                val taperStart = (x / fade).coerceIn(0f, 1f)
                val taperEnd = ((playedX - x) / fade).coerceIn(0f, 1f)
                val taper = minOf(taperStart, taperEnd)
                val y = cy + amp * taper * sin((x / wavelength) * 2.0 * PI + phase).toFloat()
                path.lineTo(x, y)
                x += 2f
            }
            path.lineTo(playedX, cy)
            drawPath(path, color = accent, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }

        if (playedX < size.width) {
            drawLine(
                color = muted,
                start = Offset(playedX, cy),
                end = Offset(size.width, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Раскрытие истории «шторкой»: линия едет вниз, строки проявляются под ней. */
@Composable
private fun CurtainReveal(
    expanded: Boolean,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    if (count <= 0) return
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "history-curtain",
    )
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.clipToBounds()) {
        SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
            val width = constraints.maxWidth.coerceAtLeast(0)
            val placeables = (0 until count).map { i ->
                subcompose(i) { content(i) }
                    .map { it.measure(Constraints.fixedWidth(width)) }
            }
            val heights = placeables.map { list -> list.maxOfOrNull { it.height } ?: 0 }
            val total = heights.sum()
            val reveal = (progress * total).roundToInt().coerceIn(0, total)
            layout(width, reveal) {
                var y = 0
                for (i in placeables.indices) {
                    val h = heights[i]
                    if (h > 0 && y < reveal) {
                        val alpha = ((reveal - y).toFloat() / h).coerceIn(0f, 1f)
                        for (p in placeables[i]) {
                            p.placeWithLayer(0, y) {
                                this.alpha = alpha
                                translationY = (1f - alpha) * 14.dp.toPx()
                            }
                        }
                    }
                    y += h
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            if (progress > 0.005f && progress < 0.995f) {
                val y = size.height - 1.dp.toPx()
                drawLine(
                    color = accent,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.18f), Color.Transparent),
                        startY = y - 18.dp.toPx(),
                        endY = y,
                    ),
                    topLeft = Offset(0f, y - 18.dp.toPx()),
                    size = Size(size.width, 18.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("В плейлист", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(
                        "Плейлистов пока нет",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyRow(Modifier.fillMaxWidth()) {
                        items(playlists) { playlist ->
                            FilterChip(
                                selected = false,
                                onClick = { onPick(playlist.id) },
                                label = { Text(playlist.name) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Новый плейлист") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            if (name.isNotBlank()) {
                                onCreate(name)
                                name = ""
                            }
                        },
                        enabled = name.isNotBlank(),
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "Создать")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

// ============================ НАСТРОЙКИ ============================

@Composable
fun SettingsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by SettingsStore.theme.collectAsState()
    val artworkScale by SettingsStore.artworkScale.collectAsState()
    val developer by SettingsStore.developer.collectAsState()
    val godMode by SettingsStore.godMode.collectAsState()
    val bypass by SettingsStore.bypass.collectAsState()
    val watchServer by SettingsStore.watchServer.collectAsState()
    val bypassState by ProxyRuntime.state.collectAsState()
    val updateInfo by vm.updateInfo.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val updateError by vm.updateError.collectAsState()

    var showMines by remember { mutableStateOf(false) }
    var showSudoku by remember { mutableStateOf(false) }
    if (showMines) {
        MinesweeperScreen(onBack = { showMines = false })
        return
    }
    if (showSudoku) {
        SudokuScreen(onBack = { showSudoku = false })
        return
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                    }
                    Text("Настройки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp),
                ) {
                    SectionTitle("Тема")
                    Column(Modifier.fillMaxWidth()) {
                        VolnaPalettes.chunked(5).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { palette ->
                                    ThemeChip(
                                        palette = palette,
                                        selected = palette.id == theme,
                                        onClick = { SettingsStore.setTheme(palette.id) },
                                        modifier = Modifier.weight(1f).aspectRatio(1f).padding(4.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            VolnaPalettes.firstOrNull { it.id == theme }?.name.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SectionTitle("Сеть")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("YouTube без VPN", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when (bypassState) {
                                    BypassState.READY -> "Работает"
                                    BypassState.STARTING -> "Запуск…"
                                    BypassState.VPN_ACTIVE -> "Пауза, включён VPN"
                                    BypassState.FAILED -> "Не удалось"
                                    BypassState.DISABLED -> "Выключено"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (bypassState) {
                                    BypassState.READY -> MaterialTheme.colorScheme.primary
                                    BypassState.FAILED -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Switch(
                            checked = bypass,
                            onCheckedChange = { ProxyRuntime.setEnabled(context, it) },
                            enabled = bypassState != BypassState.VPN_ACTIVE,
                        )
                    }
                    if (bypassState != BypassState.VPN_ACTIVE) {
                        TextButton(onClick = { ProxyRuntime.retry(context) }) { Text("Проверить") }
                    }

                    SectionTitle("Совместный просмотр")
                    OutlinedTextField(
                        value = watchServer,
                        onValueChange = { SettingsStore.setWatchServer(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://сервер-комнат") },
                        singleLine = true,
                    )

                    SectionTitle("Размер обложки")
                    LineSlider(
                        value = artworkScale,
                        valueRange = 0.6f..1.4f,
                        onValueChange = { SettingsStore.setArtworkScale(it) },
                    )

                    SectionTitle("Игры")
                    OutlinedButton(onClick = { showMines = true }) { Text("Сапёр") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showSudoku = true }) { Text("Судоку") }

                    if (developer) {
                        SectionTitle("Режим разработчика")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("god mode", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = godMode, onCheckedChange = { SettingsStore.setGodMode(it) })
                        }
                    }

                    updateInfo?.let { info ->
                        SectionTitle("Обновление")
                        Text(
                            "Доступна версия ${info.version}. У тебя ${BuildConfig.VERSION_NAME}.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        updateProgress?.let { p ->
                            LinearProgressIndicator(
                                progress = { p },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { vm.downloadAndInstallUpdate() },
                                enabled = updateProgress == null,
                            ) {
                                Text(
                                    when {
                                        updateProgress != null -> "Загрузка…"
                                        info.apkUrl == null -> "Открыть GitHub"
                                        else -> "Скачать и установить"
                                    },
                                )
                            }
                            TextButton(onClick = { vm.openUpdateRelease() }) { Text("На GitHub") }
                        }
                        updateError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    SectionTitle("Данные")
                    OutlinedButton(onClick = {
                        vm.clearCaches()
                        scope.launch {
                            context.imageLoader.memoryCache?.clear()
                            context.imageLoader.diskCache?.clear()
                        }
                    }) { Text("Очистить кэш") }

                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Версия ${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ThemeChip(
    palette: VolnaPalette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var frac by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    Box(
        modifier
            .onGloballyPositioned { coords ->
                val ws = view.width.toFloat()
                val wh = view.height.toFloat()
                if (ws > 0f && wh > 0f) {
                    val p = coords.positionInWindow()
                    frac = Offset(
                        (p.x + coords.size.width / 2f) / ws,
                        (p.y + coords.size.height / 2f) / wh,
                    )
                }
            }
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .clickable {
                ThemeReveal.trigger(frac.x, frac.y)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(if (selected) 3.dp else 5.dp)) {
            val w = size.width
            val h = size.height
            val side = minOf(w, h)
            val tl = Offset((w - side) / 2f, (h - side) / 2f)
            val half = Size(side, side)
            drawArc(Color(palette.accent), 135f, 180f, true, topLeft = tl, size = half)
            drawArc(Color(palette.bg), 315f, 180f, true, topLeft = tl, size = half)
            drawCircle(
                color = Color(palette.bg3).copy(alpha = 0.6f),
                radius = side / 2f,
                center = Offset(w / 2f, h / 2f),
                style = Stroke(width = 1.5f),
            )
        }
    }
}

// ============================ СТРОКА ТРЕКА ============================

@Composable
fun VolnaArtwork(
    model: Any?,
    isLocal: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (isLocal) R.drawable.ic_launcher_foreground else R.drawable.ic_wave),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxSize(0.5f),
        )
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )
    }
}

@Composable
private fun TrackRow(
    track: Track,
    onRemove: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VolnaArtwork(
            model = track.artworkUri,
            isLocal = track.isLocal,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (track.subtitle.isNotBlank()) {
                Text(
                    track.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = "Убрать",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (track.durationMs > 0) {
            Text(
                fmt(track.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
