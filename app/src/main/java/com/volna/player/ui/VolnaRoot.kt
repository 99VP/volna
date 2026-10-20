@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.volna.player.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.volna.player.R
import com.volna.player.net.ProxyRuntime
import com.volna.player.player.PlayerViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val PLAYER_PAGE = 3
private const val WATCH_PAGE = 4

/** Ссылка «Смотреть вместе» из volna://watch. */
data class WatchLink(
    val url: String?,
    val positionMs: Long = 0L,
    val room: String? = null,
    val server: String? = null,
)

@Composable
fun VolnaRoot(
    initialWatch: WatchLink? = null,
    onWatchConsumed: () -> Unit = {},
) {
    val vm: PlayerViewModel = viewModel()
    val context = LocalContext.current
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var watchFullscreen by remember { mutableStateOf(false) }
    var shareWatchRequest by remember { mutableIntStateOf(0) }
    var watchSettingsRequest by remember { mutableIntStateOf(0) }
    val pirat by vm.pirat.collectAsState()
    val piratMessage by vm.piratMessage.collectAsState()
    val piratQuip by vm.piratQuip.collectAsState()
    val toastMsg by vm.toast.collectAsState()
    val updateInfo by vm.updateInfo.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val updateDismissed by vm.updateDismissed.collectAsState()

    val pagerState = rememberPagerState(initialPage = PLAYER_PAGE) { 5 }
    val scope = rememberCoroutineScope()
    val activity = context as? Activity

    LaunchedEffect(watchFullscreen) {
        val a = activity ?: return@LaunchedEffect
        a.requestedOrientation = if (watchFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val controller = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (watchFullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val a = activity ?: return@onDispose
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowCompat.getInsetsController(a.window, a.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // при возврате в приложение перечитываем избранное/историю (лайк мог быть из уведомления)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.reloadLibrary()
                // Проверяем VPN и заново поднимаем локальный обход, если нужно.
                ProxyRuntime.refresh(context)
                vm.checkForUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = showSearch) { showSearch = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !watchFullscreen && pagerState.currentPage == WATCH_PAGE) {
        scope.launch { pagerState.scrollToPage(PLAYER_PAGE) }
    }
    BackHandler(enabled = watchFullscreen) { watchFullscreen = false }

    LaunchedEffect(Unit) {
        ProxyRuntime.refresh(context)
        vm.checkForUpdates()
    }

    LaunchedEffect(showSettings) {
        if (showSettings) vm.checkForUpdates(force = true)
    }

    LaunchedEffect(initialWatch) {
        val link = initialWatch ?: return@LaunchedEffect
        if (!link.url.isNullOrBlank()) vm.setWatch(link.url, link.positionMs)
        vm.setWatchRoom(link.room, link.server)
        pagerState.scrollToPage(WATCH_PAGE)
        onWatchConsumed()
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != WATCH_PAGE) watchFullscreen = false
    }

    LaunchedEffect(pirat) {
        if (pirat) {
            pagerState.scrollToPage(PLAYER_PAGE)
            showSearch = false
            showSettings = false
        }
    }

    LaunchedEffect(piratMessage) {
        if (pirat) {
            runCatching { Toast.makeText(context, piratMessage, Toast.LENGTH_SHORT).show() }
        }
    }
    LaunchedEffect(piratQuip) {
        val q = piratQuip
        if (pirat && q != null) {
            runCatching { Toast.makeText(context, q, Toast.LENGTH_SHORT).show() }
        }
    }
    LaunchedEffect(toastMsg) {
        val m = toastMsg
        if (m != null) {
            runCatching { Toast.makeText(context, m, Toast.LENGTH_SHORT).show() }
            vm.clearToast()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (pirat) {
                PiratLockedScreen(vm = vm, onSearch = { showSearch = true })
            } else if (showSettings) {
                SettingsScreen(vm = vm, onBack = { showSettings = false })
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (!watchFullscreen) {
                            Column {
                                MiniPlayer(vm) {
                                    scope.launch { pagerState.scrollToPage(PLAYER_PAGE) }
                                }
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    val page = pagerState.currentPage
                                    NavigationBarItem(
                                        selected = page == 0,
                                        onClick = { scope.launch { pagerState.scrollToPage(0) } },
                                        icon = { Icon(painterResource(R.drawable.ic_library), null) },
                                        label = { Text("Телефон") },
                                    )
                                    NavigationBarItem(
                                        selected = page == 1,
                                        onClick = { scope.launch { pagerState.scrollToPage(1) } },
                                        icon = { Icon(painterResource(R.drawable.ic_trending), null) },
                                        label = { Text("Тренды") },
                                    )
                                    NavigationBarItem(
                                        selected = page == 2,
                                        onClick = { scope.launch { pagerState.scrollToPage(2) } },
                                        icon = { Icon(painterResource(R.drawable.ic_wave), null) },
                                        label = { Text("Волна") },
                                    )
                                    NavigationBarItem(
                                        selected = page == PLAYER_PAGE || page == WATCH_PAGE,
                                        onClick = { scope.launch { pagerState.scrollToPage(PLAYER_PAGE) } },
                                        icon = { Icon(painterResource(R.drawable.ic_play), null) },
                                        label = { Text("Плеер") },
                                    )
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        if (!watchFullscreen) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .size(56.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onPress = {
                                                // Держишь 3 сек — срабатывает, не отпуская.
                                                val released = withTimeoutOrNull(3000) { tryAwaitRelease() }
                                                if (released == null) {
                                                    vm.toggleDeveloper()
                                                } else if (released) {
                                                    showSettings = true
                                                }
                                            },
                                        )
                                    },
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "Настройки")
                                }
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (!watchFullscreen) {
                            Header(
                                showBack = pagerState.currentPage != PLAYER_PAGE,
                                onBack = { scope.launch { pagerState.scrollToPage(PLAYER_PAGE) } },
                                onSearch = { showSearch = true },
                                onWatch = {
                                    showSearch = false
                                    scope.launch { pagerState.scrollToPage(WATCH_PAGE) }
                                },
                                isWatch = pagerState.currentPage == WATCH_PAGE,
                                onWatchTogether = { shareWatchRequest++ },
                                onWatchSettings = { watchSettingsRequest++ },
                            )
                        }
                        val info = updateInfo
                        if (info != null && info.buildId.ifBlank { info.version } != updateDismissed) {
                            UpdateBanner(
                                info = info,
                                progress = updateProgress,
                                onDismiss = { vm.dismissUpdate() },
                                onUpdate = { vm.downloadAndInstallUpdate() },
                                onOpenSettings = { showSettings = true },
                            )
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f),
                        ) { page ->
                            when (page) {
                                0 -> PhoneScreen(vm)
                                1 -> TrendingScreen(vm)
                                2 -> WaveScreen(vm)
                                3 -> PlayerScreen(vm)
                                else -> WatchScreen(
                                    vm = vm,
                                    fullscreen = watchFullscreen,
                                    onBack = {
                                        scope.launch { pagerState.scrollToPage(PLAYER_PAGE) }
                                    },
                                    onFullscreen = { watchFullscreen = it },
                                    shareRequest = shareWatchRequest,
                                    settingsRequest = watchSettingsRequest,
                                )
                            }
                        }
                    }
                }
            }

            if (showSearch) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { showSearch = false },
                )
            }
            AnimatedVisibility(
                visible = showSearch,
                enter = scaleIn(
                    initialScale = 0.88f,
                    animationSpec = tween(480, easing = FastOutSlowInEasing),
                    transformOrigin = TransformOrigin(1f, 0f),
                ) + fadeIn(tween(400, easing = FastOutSlowInEasing)),
                exit = scaleOut(
                    animationSpec = tween(240),
                    transformOrigin = TransformOrigin(1f, 0f),
                ) + fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                SearchOverlay(
                    vm = vm,
                    onDismiss = { showSearch = false },
                    onPlayed = {
                        scope.launch { pagerState.scrollToPage(PLAYER_PAGE) }
                        showSearch = false
                    },
                )
            }
        }
    }
}

/** Плашка о новой версии: закрывается, но остаётся в настройках. */
@Composable
private fun UpdateBanner(
    info: com.volna.player.update.UpdateInfo,
    progress: Float?,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Доступна Волна ${info.version} · ${info.buildId.ifBlank { "новая сборка" }}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Можно обновить сейчас или позже в настройках.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Закрыть")
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = onOpenSettings) { Text("Настройки") }
                TextButton(onClick = onUpdate, enabled = progress == null) {
                    Text(if (progress == null) "Обновить" else "Загрузка…")
                }
            }
        }
    }
}

@Composable
private fun Header(
    showBack: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onWatch: (() -> Unit)? = null,
    isWatch: Boolean = false,
    onWatchTogether: (() -> Unit)? = null,
    onWatchSettings: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "На плеер")
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Text("Волна", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (isWatch) {
            onWatchSettings?.let {
                IconButton(onClick = it) {
                    Icon(
                        painterResource(R.drawable.ic_more_vert),
                        contentDescription = "Настройки просмотра",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
            }
            onWatchTogether?.let {
                IconButton(onClick = it) {
                    Icon(painterResource(R.drawable.ic_people), contentDescription = "Смотреть вместе")
                }
            }
        } else {
            onWatch?.let {
                IconButton(onClick = it) {
                    Icon(painterResource(R.drawable.ic_watch), contentDescription = "Просмотр")
                }
            }
        }
        IconButton(onClick = onSearch) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = "Поиск")
        }
    }
}

/** Режим пасхалки: пауза запрещена, но треки можно менять и перематывать. */
@Composable
private fun PiratLockedScreen(vm: PlayerViewModel, onSearch: () -> Unit) {
    val message by vm.piratMessage.collectAsState()
    val quip by vm.piratQuip.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Header(showBack = false, onBack = {}, onSearch = onSearch)
        Box(Modifier.weight(1f)) {
            PlayerScreen(vm)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                quip ?: message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MiniPlayer(vm: PlayerViewModel, onExpand: () -> Unit) {
    val now by vm.now.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val current = now ?: return

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clickable { onExpand() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VolnaArtwork(
                model = current.artworkUri,
                isLocal = current.isLocal,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MarqueeTitle(
                    text = current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (current.artist.isNotBlank()) {
                    Text(
                        current.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { vm.togglePlayPause() }) {
                Icon(
                    painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = "Играть/пауза",
                )
            }
        }
    }
}
