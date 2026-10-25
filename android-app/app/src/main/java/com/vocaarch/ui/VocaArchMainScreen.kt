package com.vocaarch.ui

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vocaarch.data.SortOrder
import com.vocaarch.data.UserSettings
import com.vocaarch.data.VocabEntryEntity
import kotlinx.coroutines.delay
import kotlin.random.Random

// ============================================================================
// VocaArch Android — Milestone 2 & 4: Jetpack Compose UI
//   - 상단 메뉴바 (폰트 크기 A-/A+, 플레이 버튼 ▶, 설정 모달 ⚙️)
//   - 책제목/단원별 필터 및 5종 정렬 바
//   - 2컬럼 단어 리스트 & 눈 모양 아이콘(👁️) 토글 + 펜/손가락 필기 스크래치패드(InkScratchpadBox)
//   - 플래시카드 플레이어 (터치 시 단어/의미 토글 및 다음 단어 이동, 설정 시간 기반 자동 넘김)
// File: android-app/app/src/main/java/com/vocaarch/ui/VocaArchMainScreen.kt
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocaArchMainScreen(
    allEntries: List<VocabEntryEntity>,
    bookTitles: List<String>,
    chapters: List<String>,
    settings: UserSettings,
    onUpdateSettings: (UserSettings) -> Unit,
    onPlayAudio: (VocabEntryEntity) -> Unit,
    onRecordAttempt: (VocabEntryEntity, Boolean) -> Unit,
    onOpenOcrModal: () -> Unit,
    onOpenFileModal: () -> Unit
) {
    var selectedBook by remember { mutableStateOf<String?>(null) }
    var selectedChapter by remember { mutableStateOf<String?>(null) }
    var selectedSort by remember { mutableStateOf(SortOrder.INPUT_ORDER) }
    var randomSeed by remember { mutableIntStateOf(42) }

    // 각 컬럼 상단의 눈 모양 아이콘 상태 (ON: 텍스트 표시, OFF: 텍스트 가림 + 필기 가능)
    var isWordColumnVisible by remember { mutableStateOf(true) }
    var isMeaningColumnVisible by remember { mutableStateOf(true) }

    // 모달 상태
    var isFlashcardOpen by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }

    // 필터 및 5종 정렬 적용 리스트
    val displayedEntries = remember(allEntries, selectedBook, selectedChapter, selectedSort, randomSeed) {
        val filtered = allEntries.filter { entry ->
            val matchBook = selectedBook.isNullOrEmpty() || entry.bookTitle == selectedBook
            val matchChap = selectedChapter.isNullOrEmpty() || entry.chapterName == selectedChapter
            matchBook && matchChap
        }
        when (selectedSort) {
            SortOrder.WORD_ASC -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.word })
            SortOrder.WORD_DESC -> filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.word })
            SortOrder.RANDOM -> filtered.shuffled(Random(randomSeed))
            SortOrder.INPUT_ORDER -> filtered.sortedBy { it.seqOrder }
            SortOrder.ERROR_RATE_DESC -> filtered.sortedWith(compareByDescending<VocabEntryEntity> { it.errorRate }.thenByDescending { it.wrongCount })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VocaArch 단어장", fontWeight = FontWeight.Bold) },
                actions = {
                    // 1. 상단 메뉴바 폰트 크기 조절 버튼 (A- / 배율 / A+)
                    TextButton(
                        onClick = {
                            val next = (settings.fontSizeScale - 0.1f).coerceAtLeast(0.8f)
                            onUpdateSettings(settings.copy(fontSizeScale = next))
                        }
                    ) { Text("A-", fontWeight = FontWeight.Bold) }

                    Text(
                        text = "${(settings.fontSizeScale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )

                    TextButton(
                        onClick = {
                            val next = (settings.fontSizeScale + 0.1f).coerceAtMost(1.5f)
                            onUpdateSettings(settings.copy(fontSizeScale = next))
                        }
                    ) { Text("A+", fontWeight = FontWeight.Bold) }

                    // 2. 상단 메뉴바 플레이(▶) 버튼 -> 플래시카드 시작
                    Button(
                        onClick = { if (displayedEntries.isNotEmpty()) isFlashcardOpen = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "플래시카드 플레이")
                        Spacer(Modifier.width(4.dp))
                        Text("플레이")
                    }

                    // 3. 이미지 OCR 및 파일 입출력 버튼
                    IconButton(onClick = onOpenOcrModal) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = "이미지 OCR 단어 추가")
                    }
                    IconButton(onClick = onOpenFileModal) {
                        Icon(Icons.Default.ImportExport, contentDescription = "DB 파일 입출력")
                    }

                    // 4. 설정(⚙️) 버튼
                    IconButton(onClick = { isSettingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 책제목, 단원별 필터 및 5종 정렬 컨트롤 바
            FilterAndSortBar(
                bookTitles = bookTitles,
                chapters = chapters,
                selectedBook = selectedBook,
                selectedChapter = selectedChapter,
                selectedSort = selectedSort,
                onSelectBook = {
                    selectedBook = it
                    selectedChapter = null
                },
                onSelectChapter = { selectedChapter = it },
                onSelectSort = {
                    selectedSort = it
                    if (it == SortOrder.RANDOM) randomSeed = Random.nextInt()
                }
            )

            // 2컬럼 헤더 (각 컬럼마다 눈 모양 아이콘 👁️ 배치)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(0.42f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("영단어", fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { isWordColumnVisible = !isWordColumnVisible }) {
                        Icon(
                            imageVector = if (isWordColumnVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "영단어 컬럼 보이기/가리기 (필기 모드)"
                        )
                    }
                }

                Divider(
                    modifier = Modifier
                        .height(24.dp)
                        .width(1.dp)
                )

                Row(
                    modifier = Modifier
                        .weight(0.58f)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("의미 (품사 + 뜻 / 예문)", fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { isMeaningColumnVisible = !isMeaningColumnVisible }) {
                        Icon(
                            imageVector = if (isMeaningColumnVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "의미 컬럼 보이기/가리기 (필기 모드)"
                        )
                    }
                }
            }

            // 단어 리스트 (품사별 1행)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(displayedEntries, key = { it.id }) { entry ->
                    VocabRowItem(
                        entry = entry,
                        settings = settings,
                        isWordVisible = isWordColumnVisible,
                        isMeaningVisible = isMeaningColumnVisible,
                        onPlayAudio = { onPlayAudio(entry) },
                        onMarkWrong = { onRecordAttempt(entry, true) },
                        onMarkCorrect = { onRecordAttempt(entry, false) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // 플래시카드 모달
    if (isFlashcardOpen && displayedEntries.isNotEmpty()) {
        FlashcardPlayerDialog(
            entries = displayedEntries,
            settings = settings,
            onPlayAudio = onPlayAudio,
            onRecordAttempt = onRecordAttempt,
            onDismiss = { isFlashcardOpen = false }
        )
    }

    // 환경설정 모달
    if (isSettingsOpen) {
        SettingsDialog(
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            onDismiss = { isSettingsOpen = false }
        )
    }
}

/**
 * 개별 단어 행:
 * - 좌측 컬럼: 영단어(디폴트) + 발음기호(설정 선택) + 소리 버튼(🔊) + 오답 표시(❌)
 * - 우측 컬럼: 1줄 [품사(설정 선택) + 한글 뜻], 2줄 [예시 문장(설정 선택)]
 * - 눈 아이콘으로 안 보이게 했을 때 해당 컬럼 영역에서 펜이나 손가락으로 필기 가능
 */
@Composable
fun VocabRowItem(
    entry: VocabEntryEntity,
    settings: UserSettings,
    isWordVisible: Boolean,
    isMeaningVisible: Boolean,
    onPlayAudio: () -> Unit,
    onMarkWrong: () -> Unit,
    onMarkCorrect: () -> Unit
) {
    val scale = settings.fontSizeScale

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .IntrinsicSize(IntrinsicSize.Min)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 영단어 컬럼 (눈 아이콘 OFF 시 필기 스크래치패드 활성화)
        InkScratchpadBox(
            isHiddenForWriting = !isWordVisible,
            modifier = Modifier
                .weight(0.42f)
                .defaultMinSize(minHeight = 76.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (isWordVisible) 1f else 0f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = entry.word,
                        fontSize = (18f * scale).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onPlayAudio, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "발음 듣기",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (settings.showPhonetic && !entry.phonetic.isNullOrBlank()) {
                    Text(
                        text = entry.phonetic,
                        fontSize = (13f * scale).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 오답률 및 틀림 표시 배지
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Surface(
                        color = if (entry.errorRate >= 50.0) Color(0xFFFFEBEE) else Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "오답률 ${entry.errorRate}% (${entry.wrongCount}/${entry.attemptCount})",
                            fontSize = 11.sp,
                            color = if (entry.errorRate >= 50.0) Color(0xFFD32F2F) else Color(0xFF475569),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 2. 의미 컬럼 (1줄: 품사 + 한글 뜻, 2줄: 예시 문장 / 눈 아이콘 OFF 시 필기 활성화)
        InkScratchpadBox(
            isHiddenForWriting = !isMeaningVisible,
            modifier = Modifier
                .weight(0.58f)
                .defaultMinSize(minHeight = 76.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (isMeaningVisible) 1f else 0f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (settings.showPos && entry.partOfSpeech.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text(
                                    text = entry.partOfSpeech,
                                    fontSize = (12f * scale).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = entry.meaningKo,
                            fontSize = (16f * scale).sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 아랫줄: 예시 문장 표시
                    if (settings.showExample && !entry.exampleSentence.isNullOrBlank()) {
                        Text(
                            text = entry.exampleSentence,
                            fontSize = (13f * scale).sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // 우측 액션: 틀렸음 표시(❌) 및 암기완료(⭕) 버튼
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onMarkWrong, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = "틀렸음 표시",
                            tint = if (entry.isLastWrong) Color(0xFFD32F2F) else Color.Gray
                        )
                    }
                    IconButton(onClick = onMarkCorrect, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "맞았음 표시",
                            tint = Color(0xFF2E7D32)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 눈 모양 아이콘으로 컬럼을 가렸을 때(isHiddenForWriting == true)
 * 펜(Stylus)이나 손가락으로 자유롭게 필기할 수 있는 캔버스 컨테이너.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InkScratchpadBox(
    isHiddenForWriting: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val paths = remember { mutableStateListOf<Path>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var drawTrigger by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .then(
                if (isHiddenForWriting) {
                    Modifier
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(8.dp))
                } else Modifier
            )
            .padding(6.dp)
    ) {
        content()

        if (isHiddenForWriting) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                currentPath = Path().apply { moveTo(event.x, event.y) }
                                drawTrigger++
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                currentPath?.lineTo(event.x, event.y)
                                drawTrigger++
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                currentPath?.let { paths.add(it) }
                                currentPath = null
                                drawTrigger++
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                val stroke = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                if (drawTrigger >= 0) {
                    paths.forEach { p -> drawPath(path = p, color = Color(0xFF1E40AF), style = stroke) }
                    currentPath?.let { p -> drawPath(path = p, color = Color(0xFF1E40AF), style = stroke) }
                }
            }

            // 필기 지우개 버튼
            if (paths.isNotEmpty()) {
                IconButton(
                    onClick = { paths.clear() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "필기 지우기",
                        tint = Color.Gray
                    )
                }
            }
        }
    }
}

/**
 * 상단 플레이(▶) 버튼 클릭 시 실행되는 플래시카드 다이얼로그:
 * - 단어 또는 의미만 먼저 보여줌
 * - 카드 터치 시: 단어 <-> 의미 토글, 다음 단어로 넘어가거나 설정된 시간(초)마다 자동 넘김
 */
@Composable
fun FlashcardPlayerDialog(
    entries: List<VocabEntryEntity>,
    settings: UserSettings,
    onPlayAudio: (VocabEntryEntity) -> Unit,
    onRecordAttempt: (VocabEntryEntity, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    var showingSide by remember(currentIndex, settings.flashcardInitialSide) {
        mutableStateOf(settings.flashcardInitialSide) // "WORD" or "MEANING"
    }
    var hasToggledOnce by remember(currentIndex) { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }

    val currentEntry = entries[currentIndex.coerceIn(entries.indices)]

    // 자동 넘김 타이머 (설정에서 flashcardAutoAdvanceSec > 0 일 때 동작)
    LaunchedEffect(currentIndex, showingSide, isPaused, settings.flashcardAutoAdvanceSec) {
        val sec = settings.flashcardAutoAdvanceSec
        if (sec > 0 && !isPaused) {
            delay(sec * 1000L)
            if (!hasToggledOnce) {
                // 먼저 반대편(단어 <-> 의미)을 보여준 뒤 다음 사이클에 다음 단어로 이동
                showingSide = if (showingSide == "WORD") "MEANING" else "WORD"
                hasToggledOnce = true
            } else {
                currentIndex = (currentIndex + 1) % entries.size
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 상단 진행률 및 자동 넘김 상태
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${currentIndex + 1} / ${entries.size}",
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { isPaused = !isPaused }) {
                        Text(
                            if (settings.flashcardAutoAdvanceSec == 0) "수동 넘김"
                            else if (isPaused) "⏸ 일시정지됨"
                            else "⏱ ${settings.flashcardAutoAdvanceSec}초 자동넘김"
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "닫기")
                    }
                }

                // 중앙 플래시카드 터치 영역 (1회 터치: 단어/의미 토글, 2회 터치: 다음 단어로 이동)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .clickable {
                            if (!hasToggledOnce) {
                                showingSide = if (showingSide == "WORD") "MEANING" else "WORD"
                                hasToggledOnce = true
                            } else {
                                currentIndex = (currentIndex + 1) % entries.size
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (showingSide == "WORD") {
                            Text(
                                text = currentEntry.word,
                                fontSize = (32f * settings.fontSizeScale).sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            if (settings.showPhonetic && !currentEntry.phonetic.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = currentEntry.phonetic,
                                    fontSize = (18f * settings.fontSizeScale).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { onPlayAudio(currentEntry) }) {
                                Icon(Icons.Default.VolumeUp, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("발음 듣기")
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (settings.showPos && currentEntry.partOfSpeech.isNotBlank()) {
                                    Text(
                                        text = "[${currentEntry.partOfSpeech}] ",
                                        fontSize = (20f * settings.fontSizeScale).sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = currentEntry.meaningKo,
                                    fontSize = (26f * settings.fontSizeScale).sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (settings.showExample && !currentEntry.exampleSentence.isNullOrBlank()) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = currentEntry.exampleSentence,
                                    fontSize = (15f * settings.fontSizeScale).sp,
                                    fontStyle = FontStyle.Italic
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = if (!hasToggledOnce) "카드를 터치하면 ${if (showingSide == "WORD") "의미" else "영단어"}가 표시됩니다"
                            else "한 번 더 터치하면 다음 단어로 넘어갑니다",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }

                // 하단 오답/정답 기록 및 이전/다음 컨트롤
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (currentIndex > 0) currentIndex -= 1 else currentIndex = entries.lastIndex
                        }
                    ) { Text("이전") }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onRecordAttempt(currentEntry, true)
                                currentIndex = (currentIndex + 1) % entries.size
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) { Text("❌ 틀림") }

                        Button(
                            onClick = {
                                onRecordAttempt(currentEntry, false)
                                currentIndex = (currentIndex + 1) % entries.size
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) { Text("⭕ 외움") }
                    }

                    OutlinedButton(
                        onClick = { currentIndex = (currentIndex + 1) % entries.size }
                    ) { Text("다음") }
                }
            }
        }
    }
}

@Composable
private fun FilterAndSortBar(
    bookTitles: List<String>,
    chapters: List<String>,
    selectedBook: String?,
    selectedChapter: String?,
    selectedSort: SortOrder,
    onSelectBook: (String?) -> Unit,
    onSelectChapter: (String?) -> Unit,
    onSelectSort: (SortOrder) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("책: ${selectedBook ?: "전체"}", modifier = Modifier.clickable { onSelectBook(null) })
        Text("단원: ${selectedChapter ?: "전체"}", modifier = Modifier.clickable { onSelectChapter(null) })
        Spacer(Modifier.weight(1f))
        Text("정렬: ${selectedSort.label}", fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsDialog(
    settings: UserSettings,
    onUpdateSettings: (UserSettings) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("단어장 표시 및 플래시카드 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.showPhonetic,
                        onCheckedChange = { onUpdateSettings(settings.copy(showPhonetic = it)) }
                    )
                    Text("발음기호 표시")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.showPos,
                        onCheckedChange = { onUpdateSettings(settings.copy(showPos = it)) }
                    )
                    Text("품사 표시")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.showExample,
                        onCheckedChange = { onUpdateSettings(settings.copy(showExample = it)) }
                    )
                    Text("예시 문장 표시")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("플래시카드 자동 넘김 시간: ${settings.flashcardAutoAdvanceSec}초")
                Slider(
                    value = settings.flashcardAutoAdvanceSec.toFloat(),
                    onValueChange = { onUpdateSettings(settings.copy(flashcardAutoAdvanceSec = it.toInt())) },
                    valueRange = 0f..15f,
                    steps = 14
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("확인") }
        }
    )
}
