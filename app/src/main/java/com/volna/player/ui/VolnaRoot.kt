@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.volna.player.ui

import android.Manifest
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.volna.player.R
import com.volna.player.player.PlayerViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val PLAYER_PAGE = 3

@Composable
fun VolnaRoot() {
    val vm: PlayerViewModel = viewModel()
    val context = LocalContext.current
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val pirat by vm.pirat.collectAsState()
    val piratMessage by vm.piratMessage.collectAsState()
    val piratQuip by vm.piratQuip.collectAsState()
    val toastMsg by vm.toast.collectAsState()

    val pagerState = rememberPagerState(initialPage = PLAYER_PAGE) { 4 }
    val scope = rememberCoroutineScope()

    // при возврате в приложение перечитываем избранное/историю (лайк мог быть из уведомления)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.reloadLibrary()
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
                        Column {
                            MiniPlayer(vm) {
                                scope.launch { pagerState.animateScrollToPage(PLAYER_PAGE) }
                            }
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                val page = pagerState.currentPage
                                NavigationBarItem(
                                    selected = page == 0,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                                    icon = { Icon(painterResource(R.drawable.ic_library), null) },
                                    label = { Text("Телефон") },
                                )
                                NavigationBarItem(
                                    selected = page == 1,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                                    icon = { Icon(painterResource(R.drawable.ic_trending), null) },
                                    label = { Text("Тренды") },
                                )
                                NavigationBarItem(
                                    selected = page == 2,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                                    icon = { Icon(painterResource(R.drawable.ic_wave), null) },
                                    label = { Text("Волна") },
                                )
                                NavigationBarItem(
                                    selected = page == PLAYER_PAGE,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(PLAYER_PAGE) } },
                                    icon = { Icon(painterResource(R.drawable.ic_play), null) },
                                    label = { Text("Плеер") },
                                )
                            }
                        }
                    },
                    floatingActionButton = {
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
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        Header(
                            showBack = pagerState.currentPage != PLAYER_PAGE,
                            onBack = { scope.launch { pagerState.animateScrollToPage(PLAYER_PAGE) } },
                            onSearch = { showSearch = true },
                        )
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f),
                        ) { page ->
                            when (page) {
                                0 -> PhoneScreen(vm)
                                1 -> TrendingScreen(vm)
                                2 -> WaveScreen(vm)
                                else -> PlayerScreen(vm)
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
                        scope.launch { pagerState.animateScrollToPage(PLAYER_PAGE) }
                        showSearch = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(showBack: Boolean, onBack: () -> Unit, onSearch: () -> Unit) {
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
                Text(
                    current.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
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
