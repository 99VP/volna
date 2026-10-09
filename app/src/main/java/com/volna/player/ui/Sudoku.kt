package com.volna.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volna.player.R
import com.volna.player.data.GameSave
import com.volna.player.data.GameStore
import com.volna.player.data.SettingsStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

private data class SudokuDiff(val name: String, val holes: Int)

private val SUDOKU_DIFFS = listOf(
    SudokuDiff("Легко", 40),
    SudokuDiff("Средне", 48),
    SudokuDiff("Сложно", 54),
)

/** Генерация судоку с единственным решением. */
private object SudokuGen {

    fun generate(holes: Int): Pair<IntArray, IntArray> {
        val full = solvedGrid()
        val puzzle = full.copyOf()
        var removed = 0
        for (p in (0 until 81).shuffled(Random)) {
            if (removed >= holes) break
            val q = 80 - p
            if (puzzle[p] == 0) continue
            val a = puzzle[p]
            val b = puzzle[q]
            puzzle[p] = 0
            if (q != p) puzzle[q] = 0
            val copy = puzzle.copyOf()
            if (countSolutions(copy, 2) != 1) {
                puzzle[p] = a
                puzzle[q] = b
            } else {
                removed += if (q != p && b != 0) 2 else 1
            }
        }
        return puzzle to full
    }

    private fun solvedGrid(): IntArray {
        val g = IntArray(81)
        fun fill(pos: Int): Boolean {
            if (pos == 81) return true
            for (n in (1..9).shuffled(Random)) {
                if (canPlace(g, pos, n)) {
                    g[pos] = n
                    if (fill(pos + 1)) return true
                    g[pos] = 0
                }
            }
            return false
        }
        fill(0)
        return g
    }

    private fun canPlace(g: IntArray, pos: Int, n: Int): Boolean {
        val r = pos / 9
        val c = pos % 9
        for (i in 0 until 9) {
            if (g[r * 9 + i] == n) return false
            if (g[i * 9 + c] == n) return false
        }
        val br = (r / 3) * 3
        val bc = (c / 3) * 3
        for (rr in 0 until 3) {
            for (cc in 0 until 3) {
                if (g[(br + rr) * 9 + bc + cc] == n) return false
            }
        }
        return true
    }

    private fun countSolutions(g: IntArray, limit: Int): Int {
        var count = 0
        fun solve(pos: Int) {
            if (count >= limit) return
            var p = pos
            while (p < 81 && g[p] != 0) p++
            if (p == 81) {
                count++
                return
            }
            for (n in 1..9) {
                if (canPlace(g, p, n)) {
                    g[p] = n
                    solve(p + 1)
                    g[p] = 0
                    if (count >= limit) return
                }
            }
        }
        solve(0)
        return count
    }
}

@Composable
fun SudokuScreen(onBack: () -> Unit) {
    val savedS = remember { parseSudokuSave(GameSave.get("sudoku")) }
    var diffIndex by remember { mutableIntStateOf(savedS?.diffIndex ?: 1) }
    var given by remember { mutableStateOf(savedS?.given ?: IntArray(81)) }
    var solution by remember { mutableStateOf(savedS?.solution ?: IntArray(81)) }
    var values by remember { mutableStateOf(savedS?.values ?: IntArray(81)) }
    var notes by remember { mutableStateOf(List(81) { mutableSetOf<Int>() }) }
    var selected by remember { mutableIntStateOf(savedS?.selected ?: -1) }
    var noteMode by remember { mutableStateOf(false) }
    var solved by remember { mutableStateOf(savedS?.solved ?: false) }
    var loading by remember { mutableStateOf(savedS == null) }
    var skipGenerate by remember { mutableStateOf(savedS != null) }
    var seconds by remember { mutableIntStateOf(savedS?.seconds ?: 0) }
    var mistakes by remember { mutableIntStateOf(0) }
    var hintsLeft by remember { mutableIntStateOf(savedS?.hintsLeft ?: 4) }
    var hints by remember { mutableIntStateOf(savedS?.hints ?: 0) }
    var seed by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var newRecord by remember { mutableStateOf(false) }
    var timeStarted by remember { mutableStateOf(savedS?.timeStarted ?: false) }
    var mistakesLeft by remember { mutableIntStateOf(savedS?.mistakesLeft ?: 5) }
    var gameOver by remember { mutableStateOf(savedS?.gameOver ?: false) }
    var bestTimeNow by remember {
        mutableIntStateOf(GameStore.bestTime("sudoku_${savedS?.diffIndex ?: 1}"))
    }
    var newTimeRecord by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var soundExpanded by remember { mutableStateOf(false) }
    var loseText by remember { mutableStateOf("Провал!") }
    var justSolved by remember { mutableStateOf(false) }
    val mistakeLimit = when (diffIndex) {
        0 -> 5
        1 -> 4
        else -> 3
    }
    val soundOn by SettingsStore.sudokuSound.collectAsState()
    val soundVolume by SettingsStore.sudokuVolume.collectAsState()
    val godMode by SettingsStore.godMode.collectAsState()

    BackHandler(enabled = true) { onBack() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { Sfx.warm(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(diffIndex, seed) {
        if (skipGenerate) {
            skipGenerate = false
            loading = false
            return@LaunchedEffect
        }
        loading = true
        solved = false
        justSolved = false
        selected = -1
        seconds = 0
        mistakes = 0
        hints = 0
        score = 0
        newRecord = false
        timeStarted = false
        gameOver = false
        newTimeRecord = false
        mistakesLeft = when (diffIndex) {
            0 -> 5
            1 -> 4
            else -> 3
        }
        hintsLeft = when (diffIndex) {
            0 -> 4
            1 -> 2
            else -> 0
        }
        bestTimeNow = GameStore.bestTime("sudoku_$diffIndex")
        notes = List(81) { mutableSetOf<Int>() }
        GameStore.registerStart()
        val result = withContext(Dispatchers.Default) { SudokuGen.generate(SUDOKU_DIFFS[diffIndex].holes) }
        given = result.first
        solution = result.second
        values = result.first.copyOf()
        loading = false
    }

    LaunchedEffect(loading, solved, timeStarted, gameOver, paused) {
        while (timeStarted && !loading && !solved && !gameOver && !paused) {
            delay(1000)
            seconds++
        }
    }

    LaunchedEffect(diffIndex) {
        bestTimeNow = GameStore.bestTime("sudoku_$diffIndex")
    }

    val wave = remember { Animatable(1f) }
    LaunchedEffect(justSolved) {
        if (justSolved) {
            wave.snapTo(0f)
            wave.animateTo(1f, tween(1200))
        }
    }
    LaunchedEffect(
        diffIndex, seconds, mistakesLeft, hints, hintsLeft, timeStarted, solved, gameOver, selected,
        given, solution, values,
    ) {
        GameSave.put(
            "sudoku",
            encodeSudokuSave(
                diffIndex, seconds, mistakesLeft, hints, hintsLeft, timeStarted, solved, gameOver,
                selected, given, solution, values,
            ),
        )
    }

    // проверка ошибок: значение не совпадает с решением
    val conflict = remember(values, solution) {
        val conf = BooleanArray(81)
        for (i in 0 until 81) {
            conf[i] = values[i] != 0 && values[i] != solution[i]
        }
        conf
    }

    val remaining = remember(values) {
        val counts = IntArray(10)
        for (v in values) if (v != 0) counts[v]++
        counts
    }

    fun finishSudoku() {
        val key = "sudoku_$diffIndex"
        val sc = sudokuScore(diffIndex, seconds, mistakes, hints)
        newRecord = GameStore.submit(key, sc)
        newTimeRecord = GameStore.submitTime(key, seconds)
        score = sc
        bestTimeNow = GameStore.bestTime(key)
        justSolved = true
        if (newRecord || newTimeRecord) Sfx.record(context, GameSfx.SUDOKU) else Sfx.win(context, GameSfx.SUDOKU)
    }

    fun place(n: Int) {
        val i = selected
        if (i < 0 || given[i] != 0 || solved || gameOver) return
        timeStarted = true
        if (noteMode) {
            val set = notes[i]
            if (n in set) set.remove(n) else set.add(n)
            notes = notes.toList()
            Sfx.flag(context, GameSfx.SUDOKU)
            return
        }
        val newValues = values.copyOf()
        newValues[i] = if (newValues[i] == n) 0 else n
        values = newValues
        notes = notes.toMutableList().also { it[i] = mutableSetOf() }
        when {
            newValues[i] == 0 -> Sfx.flag(context, GameSfx.SUDOKU)
            newValues[i] == solution[i] -> Sfx.click(context, GameSfx.SUDOKU)
            else -> {
                mistakes++
                mistakesLeft--
                Sfx.boom(context, GameSfx.SUDOKU)
                if (mistakesLeft <= 0) {
                    gameOver = true
                    loseText = SUDOKU_LOSE_WORDS.random()
                }
            }
        }
        if (newValues.contentEquals(solution)) {
            solved = true
            finishSudoku()
        }
    }

    fun hint() {
        if (solved || gameOver) return
        val unlimited = SettingsStore.godMode.value
        if (!unlimited && hintsLeft <= 0) return
        timeStarted = true
        val empties = (0 until 81).filter { given[it] == 0 && values[it] != solution[it] }
        if (empties.isEmpty()) return
        val i = empties.random()
        val newValues = values.copyOf()
        newValues[i] = solution[i]
        values = newValues
        hints++
        if (!unlimited) hintsLeft--
        notes = notes.toMutableList().also { it[i] = mutableSetOf() }
        selected = i
        Sfx.click(context, GameSfx.SUDOKU)
        if (newValues.contentEquals(solution)) {
            solved = true
            finishSudoku()
        }
    }

    fun erase() {
        val i = selected
        if (i < 0 || given[i] != 0 || solved || gameOver) return
        timeStarted = true
        val newValues = values.copyOf()
        newValues[i] = 0
        values = newValues
        notes = notes.toMutableList().also { it[i] = mutableSetOf() }
        Sfx.flag(context, GameSfx.SUDOKU)
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
            Text("Судоку", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "ошибок: $mistakesLeft",
                    style = MaterialTheme.typography.titleSmall,
                    color = lerp(
                        MaterialTheme.colorScheme.onSurface,
                        MaterialTheme.colorScheme.error,
                        (1f - mistakesLeft.toFloat() / mistakeLimit).coerceIn(0f, 1f),
                    ),
                )
            }
        }

        var diffExpanded by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { diffExpanded = !diffExpanded }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { diffIndex = (diffIndex - 1 + SUDOKU_DIFFS.size) % SUDOKU_DIFFS.size }) {
                    Text("◀", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    SUDOKU_DIFFS[diffIndex].name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { diffIndex = (diffIndex + 1) % SUDOKU_DIFFS.size }) {
                    Text("▶", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (diffExpanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            AnimatedVisibility(
                visible = diffExpanded,
                enter = expandVertically(tween(220)) + fadeIn(),
                exit = shrinkVertically(tween(220)) + fadeOut(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(6.dp),
                ) {
                    SUDOKU_DIFFS.forEachIndexed { i, d ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { diffIndex = i; diffExpanded = false }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                d.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (diffIndex == i) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (bestTimeNow > 0) {
            Text(
                "Лучшее время: ${fmtTime(bestTimeNow)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = solved,
            enter = expandVertically(tween(300)) + fadeIn(),
            exit = shrinkVertically(tween(300)) + fadeOut(),
        ) {
            Text(
                "Решено! Время: ${fmtTime(seconds)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(
            visible = gameOver,
            enter = expandVertically(tween(300)) + fadeIn(),
            exit = shrinkVertically(tween(300)) + fadeOut(),
        ) {
            Text(
                loseText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gridSize = maxWidth - 8.dp
                val cell = gridSize / 9
                val lineThin = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                val lineStrong = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(gridSize)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                ) {
                    Column {
                        for (r in 0 until 9) {
                            Row {
                                for (c in 0 until 9) {
                                    val i = r * 9 + c
                                    SudokuCell(
                                        size = cell,
                                        value = values[i],
                                        given = given[i] != 0,
                                        selected = selected == i,
                                        peer = selected >= 0 && isPeer(selected, i),
                                        sameValue = selected >= 0 && values[selected] != 0 && values[i] == values[selected],
                                        conflict = conflict[i],
                                        notes = notes[i],
                                        diagonal = r + c,
                                        wave = if (solved) wave.value else 0f,
                                        onClick = { if (selected != i) { selected = i; timeStarted = true; Sfx.select(context, GameSfx.SUDOKU) } },
                                    )
                                }
                            }
                        }
                    }
                    Canvas(Modifier.matchParentSize()) {
                        val w = size.width
                        val h = size.height
                        val stepX = w / 9f
                        val stepY = h / 9f
                        val thinW = 1f
                        val strongW = 3f
                        for (k in 0..9) {
                            val strong = k % 3 == 0
                            val color = if (strong) lineStrong else lineThin
                            val sw = if (strong) strongW else thinW
                            val x = k * stepX
                            val y = k * stepY
                            drawLine(color, Offset(x, 0f), Offset(x, h), strokeWidth = sw)
                            drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = sw)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.Center) {
            for (n in 1..9) {
                NumberKey(
                    n = n,
                    left = 9 - remaining[n],
                    enabled = !loading,
                    onClick = { place(n) },
                )
                Spacer(Modifier.width(3.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { noteMode = !noteMode }) {
                    Text(
                        "Заметки",
                        color = if (noteMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { erase() }, enabled = !loading) { Text("Стереть") }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (godMode) "∞" else hintsLeft.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(16.dp),
                )
                TextButton(onClick = { hint() }, enabled = !loading && (godMode || hintsLeft > 0)) {
                    Text("Подсказка")
                }
            }
        }
        AnimatedVisibility(
            visible = soundExpanded,
            enter = expandVertically(tween(200)) + fadeIn(),
            exit = shrinkVertically(tween(200)) + fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Slider(
                    value = if (soundOn) soundVolume else 0f,
                    onValueChange = {
                        SettingsStore.setSudokuVolume(it)
                        if (it > 0f && !soundOn) SettingsStore.setSudokuSound(true)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = soundOn, onCheckedChange = { SettingsStore.setSudokuSound(it) })
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
            IconButton(onClick = { seed++ }) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Заново")
            }
        }
    }
        Confetti(visible = justSolved, modifier = Modifier.fillMaxSize())
        if (paused && !loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 76.dp, bottom = 110.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Пауза", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { paused = false }) { Text("Продолжить") }
                }
            }
        }
    }
}

private data class SudokuSave(
    val diffIndex: Int,
    val seconds: Int,
    val mistakesLeft: Int,
    val hints: Int,
    val hintsLeft: Int,
    val timeStarted: Boolean,
    val solved: Boolean,
    val gameOver: Boolean,
    val selected: Int,
    val given: IntArray,
    val solution: IntArray,
    val values: IntArray,
)

private fun gridStr(g: IntArray): String {
    val sb = StringBuilder(81)
    for (v in g) sb.append(v.coerceIn(0, 9))
    return sb.toString()
}

private fun strGrid(s: String): IntArray = IntArray(81) { i ->
    if (i < s.length) (s[i] - '0').coerceIn(0, 9) else 0
}

private fun encodeSudokuSave(
    diffIndex: Int, seconds: Int, mistakesLeft: Int, hints: Int, hintsLeft: Int,
    timeStarted: Boolean, solved: Boolean, gameOver: Boolean, selected: Int,
    given: IntArray, solution: IntArray, values: IntArray,
): String = listOf(
    diffIndex, seconds, mistakesLeft, hints,
    if (timeStarted) 1 else 0, if (solved) 1 else 0, if (gameOver) 1 else 0, selected,
    gridStr(given), gridStr(solution), gridStr(values), hintsLeft,
).joinToString("|")

private fun parseSudokuSave(raw: String?): SudokuSave? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val p = raw.split('|')
        SudokuSave(
            diffIndex = p[0].toInt(),
            seconds = p[1].toInt(),
            mistakesLeft = p[2].toInt(),
            hints = p[3].toInt(),
            hintsLeft = p.getOrNull(11)?.toIntOrNull() ?: 4,
            timeStarted = p[4] == "1",
            solved = p[5] == "1",
            gameOver = p[6] == "1",
            selected = p[7].toInt(),
            given = strGrid(p.getOrNull(8).orEmpty()),
            solution = strGrid(p.getOrNull(9).orEmpty()),
            values = strGrid(p.getOrNull(10).orEmpty()),
        )
    }.getOrNull()
}

private val SUDOKU_LOSE_WORDS = listOf(
    "Провал!",
    "Ошибок больше нет",
    "Не в этот раз",
    "Эх, почти",
    "Как так?!",
    "Слишком много ошибок",
)

/** Очки за решённое судоку: база по сложности + бонус за скорость − штрафы. */
private fun sudokuScore(diffIndex: Int, seconds: Int, mistakes: Int, hints: Int): Int {
    val base = when (diffIndex) {
        0 -> 300
        1 -> 600
        else -> 1000
    }
    val timeBonus = (600 - seconds).coerceAtLeast(0)
    val penalty = mistakes * 40 + hints * 120
    return (base + timeBonus - penalty).coerceAtLeast(50)
}

private fun isPeer(a: Int, b: Int): Boolean {    if (a == b) return false
    val ar = a / 9; val ac = a % 9
    val br = b / 9; val bc = b % 9
    if (ar == br || ac == bc) return true
    return (ar / 3 == br / 3) && (ac / 3 == bc / 3)
}

@Composable
private fun SudokuCell(
    size: Dp,
    value: Int,
    given: Boolean,
    selected: Boolean,
    peer: Boolean,
    sameValue: Boolean,
    conflict: Boolean,
    notes: Set<Int>,
    diagonal: Int,
    wave: Float,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            conflict && value != 0 -> MaterialTheme.colorScheme.error.copy(alpha = 0.30f)
            sameValue -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            peer -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "sudokuCell",
    )
    val waveColor = if (wave > 0f && wave * 17f >= diagonal) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    } else {
        Color.Transparent
    }
    Box(
        Modifier
            .size(size)
            .background(bg)
            .background(waveColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (value != 0) {
            Text(
                text = value.toString(),
                color = when {
                    conflict -> MaterialTheme.colorScheme.error
                    given -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.primary
                },
                fontWeight = if (given) FontWeight.SemiBold else FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        } else if (notes.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                for (r in 0 until 3) {
                    Row {
                        for (c in 0 until 3) {
                            val n = r * 3 + c + 1
                            Text(
                                text = if (n in notes) n.toString() else " ",
                                fontSize = (size.value * 0.24f).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(size / 3),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberKey(n: Int, left: Int, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(n.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            text = if (left > 0) left.toString() else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
