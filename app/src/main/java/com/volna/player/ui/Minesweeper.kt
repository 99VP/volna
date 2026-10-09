package com.volna.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.volna.player.R
import com.volna.player.data.GameSave
import com.volna.player.data.GameStore
import com.volna.player.data.SettingsStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class Level(val name: String, val size: Int, val mines: Int)

private val LEVELS = listOf(
    Level("Легко", 6, 6),
    Level("Средне", 8, 10),
    Level("Сложно", 12, 30),
)
private const val CUSTOM = 3

private data class MCell(
    val mine: Boolean = false,
    val revealed: Boolean = false,
    val flagged: Boolean = false,
    val near: Int = 0,
)

/** Расстояние по Чебышёву (для «круглой» волны). */
private fun chessboard(a: Int, b: Int, size: Int): Int {
    val ar = a / size
    val ac = a % size
    val br = b / size
    val bc = b % size
    return maxOf(kotlin.math.abs(ar - br), kotlin.math.abs(ac - bc))
}

private fun neighbors(index: Int, size: Int): List<Int> {    val r = index / size
    val c = index % size
    val out = ArrayList<Int>(8)
    for (dr in -1..1) {
        for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val nr = r + dr
            val nc = c + dc
            if (nr in 0 until size && nc in 0 until size) out.add(nr * size + nc)
        }
    }
    return out
}

@Composable
fun MinesweeperScreen(onBack: () -> Unit) {
    val saved = remember { parseMinesSave(GameSave.get("mines")) }
    var level by remember { mutableIntStateOf(saved?.level ?: 1) } // 0..2 уровни, 3 = своё
    var customSize by remember { mutableIntStateOf(saved?.customSize ?: 10) }
    var customMines by remember { mutableIntStateOf(saved?.customMines ?: 20) }
    var expanded by remember { mutableStateOf(false) }
    val size = if (level == CUSTOM) customSize else LEVELS[level].size
    val mines = if (level == CUSTOM) {
        customMines.coerceIn(1, (size * size - 9).coerceAtLeast(1))
    } else {
        LEVELS[level].mines
    }
    val soundOn by SettingsStore.minesSound.collectAsState()
    val soundVolume by SettingsStore.minesVolume.collectAsState()

    var cells by remember {
        mutableStateOf(saved?.cells?.takeIf { it.size == size * size } ?: List(size * size) { MCell() })
    }
    var started by remember { mutableStateOf(saved?.started ?: false) }
    var status by remember { mutableIntStateOf(saved?.status ?: 0) } // 0 играем, 1 победа, 2 проигрыш
    var flags by remember { mutableIntStateOf(saved?.flags ?: 0) }
    var seconds by remember { mutableIntStateOf(saved?.seconds ?: 0) }
    var newRecord by remember { mutableStateOf(false) }
    var customExpanded by remember { mutableStateOf(false) }
    var bestTimeNow by remember {
        mutableIntStateOf(
            GameStore.bestTime(if (level == CUSTOM) "custom_${size}_${mines}" else "level_$level"),
        )
    }
    var timeRecord by remember { mutableStateOf(false) }
    var lossText by remember { mutableStateOf("Бум! Ещё разок") }
    var paused by remember { mutableStateOf(false) }
    var soundExpanded by remember { mutableStateOf(false) }
    var justWon by remember { mutableStateOf(false) }

    val gameKey = if (level == CUSTOM) "custom_${size}_${mines}" else "level_$level"
    val levelName = if (level == CUSTOM) "Своё" else LEVELS[level].name

    BackHandler(enabled = true) { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Sfx.warm(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun reset(s: Int = size) {
        cells = List(s * s) { MCell() }
        started = false
        status = 0
        flags = 0
        seconds = 0
        newRecord = false
        timeRecord = false
        justWon = false
        lossText = LOSS_WORDS.random()
        bestTimeNow = GameStore.bestTime(gameKey)
    }

    fun selectLevel(i: Int) {
        level = i
        expanded = false
        reset(if (i == CUSTOM) customSize else LEVELS[i].size)
    }

    fun cycle(delta: Int) {
        val n = LEVELS.size + 1
        selectLevel(((level + delta) % n + n) % n)
    }

    LaunchedEffect(started, status, level, paused) {
        while (started && status == 0 && !paused) {
            delay(1000)
            seconds++
        }
    }

    // Сохраняем незавершённую партию, чтобы не сбрасывалась.
    LaunchedEffect(level, customSize, customMines, started, status, flags, seconds, cells) {
        GameSave.put(
            "mines",
            encodeMinesSave(level, customSize, customMines, started, status, flags, seconds, cells),
        )
    }

    // Лучшее время пересчитываем при смене сложности.
    LaunchedEffect(gameKey) {
        bestTimeNow = GameStore.bestTime(gameKey)
    }

    fun placeMines(safe: Int) {
        GameStore.registerStart()
        val forbidden = (neighbors(safe, size) + safe).toHashSet()
        val pool = (0 until size * size).filter { it !in forbidden }.toMutableList()
        pool.shuffle(Random())
        val mineSet = pool.take(mines).toHashSet()
        cells = cells.mapIndexed { i, _ ->
            MCell(mine = i in mineSet, near = neighbors(i, size).count { it in mineSet })
        }
    }

    fun revealCell(index: Int) {
        if (status != 0) return
        var list = cells
        if (list[index].revealed || list[index].flagged) return
        if (!started) {
            placeMines(index)
            started = true
            list = cells
        }
        if (list[index].mine) {
            if (SettingsStore.godMode.value) return // god mode: бомбы не взрываются
            status = 2
            lossText = LOSS_WORDS.random()
            GameStore.registerLoss(gameKey)
            Sfx.boom(context, GameSfx.MINES)
            // Бомбы вскрываются волной от задетой клетки.
            val order = list.indices.filter { list[it].mine }
                .sortedBy { chessboard(it, index, size) }
            scope.launch {
                for (m in order) {
                    cells = cells.mapIndexed { i, c -> if (i == m) c.copy(revealed = true) else c }
                    delay(50)
                }
            }
            return
        }
        val revealed = list.toMutableList()
        val stack = ArrayDeque<Int>()
        stack.addLast(index)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (revealed[i].revealed || revealed[i].flagged) continue
            revealed[i] = revealed[i].copy(revealed = true)
            if (revealed[i].near == 0) {
                neighbors(i, size).forEach { if (!revealed[it].revealed) stack.addLast(it) }
            }
        }
        cells = revealed
        Sfx.click(context, GameSfx.MINES)
        if (cells.none { !it.revealed && !it.mine }) {
            cells = cells.map {
                if (it.mine) it.copy(revealed = true, flagged = false) else it.copy(flagged = false)
            }
            flags = 0
            status = 1
            justWon = true
            val sc = minesScore(size, mines, seconds, gameKey)
            newRecord = GameStore.submit(gameKey, sc)
            timeRecord = GameStore.submitTime(gameKey, seconds)
            bestTimeNow = GameStore.bestTime(gameKey)
            if (newRecord || timeRecord) Sfx.record(context, GameSfx.MINES) else Sfx.win(context, GameSfx.MINES)
        }
    }

    /** Тап по цифре: если рядом стоит столько же флажков - открыть остальных соседей. */
    fun reveal(index: Int) {
        if (status != 0) return
        val cell = cells[index]
        if (cell.revealed && cell.near > 0) {
            val nbrs = neighbors(index, size)
            val god = SettingsStore.godMode.value
            // god mode: открываем соседей, автоматически обходя бомбы (без флажков)
            if (god || nbrs.count { cells[it].flagged } >= cell.near) {
                nbrs.forEach {
                    if (!cells[it].flagged && !cells[it].revealed && (god && cells[it].mine).not()) {
                        revealCell(it)
                    }
                }
            }
            return
        }
        revealCell(index)
    }

    fun toggleFlag(index: Int) {
        if (status != 0) return
        var list = cells
        if (list[index].revealed) return
        // Если флаг ставится первым - генерируем поле, чтобы под флагом не было мины.
        if (!started) {
            placeMines(index)
            started = true
            list = cells
        }
        if (!list[index].flagged && flags >= mines) return
        val updated = list.toMutableList()
        updated[index] = list[index].copy(flagged = !list[index].flagged)
        cells = updated
        flags = updated.count { it.flagged }
        Sfx.flag(context, GameSfx.MINES)
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
            }
            Text("Сапёр", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("⏱ ${fmtTime(seconds)}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text("💣 ${(mines - flags).coerceAtLeast(0)}", style = MaterialTheme.typography.titleMedium)
        }

        // Плашка сложности: стрелки слева/справа + раскрытие вниз.
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { cycle(-1) }) { Text("◀", style = MaterialTheme.typography.titleMedium) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(levelName, style = MaterialTheme.typography.titleMedium)
                    if (level == CUSTOM) {
                        Text(
                            "$size×$size · $mines мин",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { cycle(1) }) { Text("▶", style = MaterialTheme.typography.titleMedium) }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(6.dp),
                ) {
                    (LEVELS.map { it.name } + "Своё").forEachIndexed { i, name ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectLevel(i) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (level == i) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        if (level == CUSTOM) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { customExpanded = !customExpanded }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Поле $customSize×$customSize · $customMines мин",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(if (customExpanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
                }
                AnimatedVisibility(
                    visible = customExpanded,
                    enter = expandVertically(animationSpec = tween(220)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Поле: $customSize × $customSize", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = customSize.toFloat(),
                            valueRange = 6f..14f,
                            steps = 7,
                            onValueChange = {
                                customSize = it.toInt()
                                reset(customSize)
                            },
                        )
                        Text("Мин: $customMines", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = customMines.toFloat(),
                            valueRange = 1f..((customSize * customSize) / 3).toFloat().coerceAtLeast(1f),
                            onValueChange = {
                                customMines = it.toInt()
                                reset(customSize)
                            },
                        )
                    }
                }
            }
        }

        if (bestTimeNow > 0) {
            Text(
                "Лучшее время: ${fmtTime(bestTimeNow)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = status != 0,
            enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(),
        ) {
            Text(
                text = if (status == 1) {
                    "Победа! Время: ${fmtTime(seconds)}"
                } else {
                    lossText
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (status == 1) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                textAlign = TextAlign.Center,
            )
        }

        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            val inset = 6.dp
            val avail = minOf((maxWidth - inset * 2) / size, (maxHeight - inset * 2) / size)
            val cell = avail.value.toInt().dp - 4.dp
            val grid = if (cells.size == size * size) cells else List(size * size) { MCell() }
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(inset),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    for (r in 0 until size) {
                        Row {
                            for (c in 0 until size) {
                                val i = r * size + c
                                MineCell(
                                    cell = grid[i],
                                    size = cell,
                                    onClick = { reveal(i) },
                                    onLongClick = { toggleFlag(i) },
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = soundExpanded,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Slider(
                    value = if (soundOn) soundVolume else 0f,
                    onValueChange = {
                        SettingsStore.setMinesVolume(it)
                        if (it > 0f && !soundOn) SettingsStore.setMinesSound(true)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = soundOn, onCheckedChange = { SettingsStore.setMinesSound(it) })
            }
        }
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { soundExpanded = !soundExpanded }) {
                Icon(painterResource(R.drawable.ic_volume), contentDescription = "Звук")
            }
            IconButton(onClick = { paused = !paused }) {
                Icon(
                    painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                    contentDescription = if (paused) "Продолжить" else "Пауза",
                )
            }
            IconButton(onClick = { reset() }) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Заново")
            }
        }
        Spacer(Modifier.height(28.dp))
    }
        Confetti(visible = justWon, modifier = Modifier.fillMaxSize())
        if (paused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 76.dp, bottom = 110.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Пауза", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = { paused = false }) { Text("Продолжить") }
                }
            }
        }
    }
}

@Composable
private fun MineCell(cell: MCell, size: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(size * 0.22f)
    val targetBg = when {
        cell.revealed && cell.mine -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        cell.revealed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val bg by animateColorAsState(targetBg, animationSpec = tween(220), label = "cellBg")

    val scale by animateFloatAsState(
        targetValue = if (cell.revealed) 1f else 0.97f,
        animationSpec = tween(160),
        label = "cellScale",
    )

    Box(
        Modifier
            .padding(2.dp)
            .size(size)
            .clip(shape)
            .background(bg)
            .then(
                if (!cell.revealed) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), shape)
                } else {
                    Modifier
                },
            )
            .pointerInput(cell) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val quickRelease = withTimeoutOrNull(400) { waitForUpOrCancellation() }
                    if (quickRelease != null) {
                        onClick()
                    } else {
                        // Держит — ставим/снимаем флаг, даже если потянул палец.
                        onLongClick()
                        waitForUpOrCancellation()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = cell.revealed || cell.flagged,
            enter = scaleIn(animationSpec = tween(180)) + fadeIn(animationSpec = tween(180)),
            // Флажок «падает» вниз, а не просто исчезает.
            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(260)),
        ) {
            when {
                cell.flagged && !cell.revealed -> FlagMark()
                cell.revealed && cell.mine -> BombMark()
                cell.near > 0 -> Text(
                    text = cell.near.toString(),
                    color = numberColor(cell.near),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Spacer(Modifier.size(1.dp))
            }
        }
    }
}

/** Флажок (векторно, без эмодзи). Не уменьшается слишком сильно на мелких клетках. */
@Composable
private fun FlagMark() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.14f).coerceAtLeast(2f)
        drawLine(
            color = color,
            start = Offset(w * 0.28f, h * 0.12f),
            end = Offset(w * 0.28f, h * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val flag = Path().apply {
            moveTo(w * 0.28f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.32f)
            lineTo(w * 0.28f, h * 0.5f)
            close()
        }
        drawPath(flag, color)
    }
}

/** Взрыв (минималистично, под тему). */
@Composable
private fun BombMark() {
    val color = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxSize().padding(5.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.42f
        val stroke = (r * 0.24f).coerceAtLeast(1.4f)
        for (i in 0 until 8) {
            val a = i * (Math.PI / 4).toFloat()
            val kx = kotlin.math.cos(a)
            val ky = kotlin.math.sin(a)
            drawLine(
                color = color,
                start = Offset(cx + kx * r * 0.35f, cy + ky * r * 0.35f),
                end = Offset(cx + kx * r, cy + ky * r),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = color, radius = r * 0.32f, center = Offset(cx, cy))
    }
}

/** Очки за победу в Сапёре (считаются, но в интерфейсе не показываются). */
private fun minesScore(size: Int, mines: Int, seconds: Int, key: String): Int {
    val base = mines * 12 + size * 5
    val target = (size * size * 0.5).toInt()
    val timeBonus = (target - seconds).coerceAtLeast(0) * 3
    val streakBonus = GameStore.streak(key) * 25
    return base + timeBonus + streakBonus
}

private val LOSS_WORDS = listOf(
    "Бум!",
    "Ты проиграл",
    "Взрыв!",
    "Бум, ещё разок",
    "Как так?!",
    "Не повезло",
    "Опять мина",
)

internal fun fmtTime(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

private data class MinesSave(
    val level: Int,
    val customSize: Int,
    val customMines: Int,
    val started: Boolean,
    val status: Int,
    val flags: Int,
    val seconds: Int,
    val cells: List<MCell>,
)

private fun encodeMinesSave(
    level: Int, customSize: Int, customMines: Int,
    started: Boolean, status: Int, flags: Int, seconds: Int, cells: List<MCell>,
): String {
    val sb = StringBuilder(cells.size)
    for (c in cells) {
        var v = 0
        if (c.mine) v += 1
        if (c.revealed) v += 2
        if (c.flagged) v += 4
        sb.append(v)
    }
    return listOf(
        level, customSize, customMines,
        if (started) 1 else 0, status, flags, seconds, sb.toString(),
    ).joinToString("|")
}

private fun decodeCells(s: String, size: Int): List<MCell> {
    val raw = ArrayList<MCell>(size * size)
    for (ch in s) {
        val v = ch - '0'
        raw.add(MCell(mine = v and 1 != 0, revealed = v and 2 != 0, flagged = v and 4 != 0))
    }
    return raw.mapIndexed { i, c -> c.copy(near = neighbors(i, size).count { raw[it].mine }) }
}

private fun parseMinesSave(raw: String?): MinesSave? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val p = raw.split('|')
        val level = p[0].toInt()
        val customSize = p[1].toInt()
        val customMines = p[2].toInt()
        val size = if (level == CUSTOM) customSize else LEVELS[level].size
        val started = p[3] == "1"
        val status = p[4].toInt()
        val flags = p[5].toInt()
        val seconds = p[6].toInt()
        val cellStr = p.getOrNull(7).orEmpty()
        val cells = if (cellStr.length == size * size) decodeCells(cellStr, size)
        else List(size * size) { MCell() }
        MinesSave(level, customSize, customMines, started, status, flags, seconds, cells)
    }.getOrNull()
}

private fun numberColor(near: Int): Color = when (near) {    1 -> Color(0xFF5B8CFF)
    2 -> Color(0xFF4CAF50)
    3 -> Color(0xFFE57373)
    4 -> Color(0xFF9C6BD0)
    5 -> Color(0xFFD19A53)
    6 -> Color(0xFF4FB3A6)
    7 -> Color(0xFFE0E0E0)
    8 -> Color(0xFF9E9E9E)
    else -> Color.Unspecified
}
