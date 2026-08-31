package cn.sysu.kcb.ui.friends

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.repo.SharePack
import cn.sysu.kcb.domain.SemesterRange
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.notify.ClassAlarmScheduler
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.course.CourseDetailSheet
import cn.sysu.kcb.ui.theme.KcbTopBar
import cn.sysu.kcb.ui.timetable.TimetableGrid
import cn.sysu.kcb.ui.timetable.WeekPickerDialog
import cn.sysu.kcb.ui.timetable.WeekTitleText
import cn.sysu.kcb.ui.timetable.displayedWeekNo
import cn.sysu.kcb.ui.timetable.resolveWeekStart
import cn.sysu.kcb.ui.timetable.weekRangeLabel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendScreen(viewModel: AppViewModel, onSetupSync: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val webdavBusy by viewModel.webdavBusy.collectAsStateWithLifecycle()
    val friends by KcbApp.instance.container.friends.observe().collectAsStateWithLifecycle(emptyList())
    var pane by rememberSaveable { mutableStateOf("timetable") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var userPickedFriend by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        if (settings.webdavUrl.isNotBlank() &&
            settings.webdavNickname.isNotBlank() &&
            now - settings.webdavLastSyncAt > 10 * 60 * 1000L
        ) {
            viewModel.refreshFriends(silent = true)
        }
    }

    LaunchedEffect(friends, settings.selectedFriendId, userPickedFriend) {
        if (friends.isEmpty()) {
            selectedId = ""
            return@LaunchedEffect
        }
        val preferred = when {
            userPickedFriend && friends.any { it.id == selectedId } -> selectedId
            friends.any { it.id == settings.selectedFriendId } -> settings.selectedFriendId
            else -> friends.first().id
        }
        if (preferred != selectedId) selectedId = preferred
        if (preferred != settings.selectedFriendId) viewModel.setSelectedFriend(preferred)
    }

    val selected = friends.firstOrNull { it.id == selectedId } ?: friends.firstOrNull()
    val pack = remember(selected?.payload, selected?.id) {
        selected?.let { runCatching { KcbApp.instance.container.friends.decode(it) }.getOrNull() }
    }

    Scaffold(
        topBar = {
            KcbTopBar(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    selected?.nickname ?: "好友",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                    maxLines = 1,
                )
                if (selected != null) {
                    TextButton(onClick = { pane = "timetable" }) {
                        Text(
                            "课表",
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (pane == "timetable") 1f else 0.65f),
                            fontWeight = if (pane == "timetable") FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    TextButton(onClick = { pane = "exam" }) {
                        Text(
                            "考试",
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (pane == "exam") 1f else 0.65f),
                            fontWeight = if (pane == "exam") FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refreshFriends(silent = false) },
                        enabled = !webdavBusy,
                    ) {
                        if (webdavBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Sync, contentDescription = "同步好友")
                        }
                    }
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (friends.size > 1) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    friends.forEach { friend ->
                        FilterChip(
                            selected = friend.id == selected?.id,
                            onClick = {
                                userPickedFriend = true
                                selectedId = friend.id
                                viewModel.setSelectedFriend(friend.id)
                            },
                            label = { Text(friend.nickname) },
                        )
                    }
                }
            }
            when {
                selected == null -> FriendEmpty(onSetupSync = onSetupSync)
                pack == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这份好友课表无法读取", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                pane == "exam" -> FriendExamPane(pack)
                else -> FriendTimetablePane(pack, settings.themeColor)
            }
        }
    }
}

@Composable
private fun FriendEmpty(onSetupSync: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            "还没有好友课表",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "同一网盘账号、不同昵称上传后会出现在这里",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onSetupSync) { Text("去设置同步") }
    }
}

@Composable
private fun FriendTimetablePane(pack: SharePack, themeColor: Long) {
    val semesterOptions = remember(pack) { packSemesterOptions(pack) }
    var semester by rememberSaveable(pack.exportedAt) { mutableStateOf(pickPackSemester(pack, semesterOptions)) }
    var semesterMenu by remember { mutableStateOf(false) }
    var selectedWeek by rememberSaveable(semester, pack.exportedAt) { mutableIntStateOf(0) }
    var userPickedWeek by rememberSaveable(semester, pack.exportedAt) { mutableStateOf(false) }
    var termOverview by rememberSaveable(semester, pack.exportedAt) { mutableStateOf(false) }
    var weekPicker by remember { mutableStateOf(false) }
    var viewingCourses by remember { mutableStateOf<List<CourseEntity>?>(null) }

    val courses = pack.courses.filter { it.acadYearSemester == semester }
    val weeks = pack.weeks.filter { it.acadYearSemester == semester }
    val periods = pack.periods.filter { it.acadYearSemester == semester }
    val semesterEntity = pack.semesters.firstOrNull { it.acadYearSemester == semester }
    val maxWeek = packMaxWeek(weeks, courses)
    val academicWeek = ClassAlarmScheduler.resolveWeek(
        date = LocalDate.now(),
        weeks = weeks,
        semesterStartMillis = semesterEntity?.startMillis ?: 0L,
    )

    LaunchedEffect(semester, weeks, semesterEntity?.startMillis, userPickedWeek) {
        if (!userPickedWeek) {
            selectedWeek = academicWeek ?: weeks.firstOrNull { it.weekly > 0 }?.weekly ?: 1
        }
    }

    val pageCount = maxWeek.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = (selectedWeek - 1).coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )
    var syncingPager by remember { mutableStateOf(false) }
    LaunchedEffect(selectedWeek, pageCount) {
        val target = (selectedWeek - 1).coerceIn(0, pageCount - 1)
        if (pagerState.currentPage == target) {
            syncingPager = false
            return@LaunchedEffect
        }
        syncingPager = true
        try {
            if (userPickedWeek) pagerState.animateScrollToPage(target) else pagerState.scrollToPage(target)
        } finally {
            syncingPager = false
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collectLatest { page ->
                if (syncingPager || selectedWeek <= 0) return@collectLatest
                val week = page + 1
                if (week != selectedWeek) {
                    userPickedWeek = true
                    selectedWeek = week
                }
            }
    }
    val sheetBottomInset = WindowInsets.safeDrawing
        .union(WindowInsets.systemBars)
        .asPaddingValues()
        .calculateBottomPadding()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    if (termOverview) {
                        Text("学期课表", fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                    } else {
                        WeekTitleText(
                            pagerState = pagerState,
                            weeks = weeks,
                            selectedWeek = selectedWeek,
                            syncingPager = syncingPager,
                        )
                        Text(
                            weekRangeLabel(
                                weekNo = displayedWeekNo(pagerState, selectedWeek, syncingPager),
                                weeks = weeks,
                                semesterStartMillis = semesterEntity?.startMillis ?: 0L,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { semesterMenu = true }) {
                    Text(semester.ifBlank { "学年" })
                }
                DropdownMenu(expanded = semesterMenu, onDismissRequest = { semesterMenu = false }) {
                    semesterOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                userPickedWeek = false
                                semester = option
                                semesterMenu = false
                            },
                        )
                    }
                }
            }
            TextButton(onClick = { weekPicker = true }) { Text("周次") }
        }
        Box(Modifier.weight(1f)) {
            if (courses.isEmpty() && weeks.isEmpty() && periods.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("该学期暂无课表", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            } else if (termOverview) {
                TimetableGrid(
                    periods = periods,
                    courses = courses,
                    weekStart = resolveWeekStart(
                        academicWeek ?: selectedWeek,
                        weeks.firstOrNull { it.weekly == (academicWeek ?: selectedWeek) },
                        weeks,
                        semesterEntity?.startMillis ?: 0L,
                    ),
                    onCourses = { viewingCourses = it },
                    onEmpty = null,
                    themeColor = themeColor,
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    val weekNo = page + 1
                    val weekEntity = weeks.firstOrNull { it.weekly == weekNo }
                    TimetableGrid(
                        periods = periods,
                        courses = courses.filter { WeekMask.has(it.weeksMask, weekNo) },
                        weekStart = resolveWeekStart(weekNo, weekEntity, weeks, semesterEntity?.startMillis ?: 0L),
                        onCourses = { viewingCourses = it },
                        onEmpty = null,
                        themeColor = themeColor,
                    )
                }
            }
            viewingCourses?.let { group ->
                CourseDetailSheet(
                    courses = group,
                    periods = periods,
                    themeColor = themeColor,
                    onDismiss = { viewingCourses = null },
                    onEdit = null,
                    bottomInset = sheetBottomInset,
                )
            }
        }
    }
    if (weekPicker) {
        WeekPickerDialog(
            weeks = weeks,
            selectedWeek = selectedWeek,
            currentWeek = academicWeek,
            maxWeek = maxWeek,
            termOverview = termOverview,
            onPick = { weekNo ->
                termOverview = false
                userPickedWeek = true
                syncingPager = true
                selectedWeek = weekNo
                weekPicker = false
            },
            onPickAll = {
                termOverview = true
                weekPicker = false
            },
            onDismiss = { weekPicker = false },
        )
    }
}

@Composable
private fun FriendExamPane(pack: SharePack) {
    val allExams = pack.exams
    val semesterOptions = remember(pack) {
        (packSemesterOptions(pack) + allExams.map { it.acadYearSemester }).distinct()
    }
    var examSemester by rememberSaveable(pack.exportedAt) {
        mutableStateOf(pickPackExamSemester(pack, semesterOptions))
    }
    var selectedWeekId by rememberSaveable(examSemester, pack.exportedAt) { mutableStateOf("all") }
    var semesterMenu by remember { mutableStateOf(false) }
    val examWeeks = pack.examWeeks.filter { it.acadYearSemester == examSemester }
    val exams = allExams.filter { it.acadYearSemester == examSemester }
    val visible = exams.filter { selectedWeekId == "all" || it.examWeekId == selectedWeekId }

    LaunchedEffect(examSemester) { selectedWeekId = "all" }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("考试", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Box {
                TextButton(onClick = { semesterMenu = true }) {
                    Text(examSemester.ifBlank { "学年" })
                }
                DropdownMenu(expanded = semesterMenu, onDismissRequest = { semesterMenu = false }) {
                    semesterOptions.forEach { option ->
                        val count = allExams.count { it.acadYearSemester == option }
                        DropdownMenuItem(
                            text = {
                                Text(buildString {
                                    append(option)
                                    if (count > 0) append(" · $count")
                                })
                            },
                            onClick = {
                                examSemester = option
                                semesterMenu = false
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = examWeeks.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedWeekId == "all",
                    onClick = { selectedWeekId = "all" },
                    label = { Text("全部") },
                )
                examWeeks.forEach { week ->
                    val count = exams.count { it.examWeekId == week.examWeekId }
                    FilterChip(
                        selected = selectedWeekId == week.examWeekId,
                        onClick = { selectedWeekId = week.examWeekId },
                        label = { Text("${shortWeekName(week.examWeekName)}${if (count > 0) " $count" else ""}") },
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该学期暂无考试", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(visible, key = { index, exam -> examKey(exam, index) }) { _, exam ->
                    FriendExamCard(exam)
                }
            }
        }
    }
}

@Composable
private fun FriendExamCard(exam: ExamEntity) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(exam.subjectName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${exam.examDate}  ${exam.startTime}-${exam.endTime}")
                if (exam.classroom.isNotBlank()) Text(exam.classroom)
                val tags = listOf(exam.examWeekName, exam.examMode, exam.examStage).filter { it.isNotBlank() }
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FriendExamCountdown(exam.examDate)
        }
    }
}

@Composable
private fun FriendExamCountdown(examDate: String) {
    val days = remainingDays(examDate) ?: return
    val color = when {
        days < 0 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        days == 0 -> MaterialTheme.colorScheme.primary
        days <= 3 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier.width(64.dp).padding(start = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            days < 0 -> Text("已结束", color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            days == 0 -> Text("今天", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            days == 1 -> Text("明天", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            else -> {
                Text("$days", color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                Text("天后", color = color.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
    }
}

private fun remainingDays(examDate: String): Int? {
    val date = runCatching { LocalDate.parse(examDate.take(10)) }.getOrNull() ?: return null
    return ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()
}

private fun shortWeekName(name: String): String = when {
    name.contains("缓补") -> "缓补考"
    name.contains("结课") -> "结课考试"
    name.contains("期末") -> "期末考"
    else -> name
}

private fun examKey(exam: ExamEntity, index: Int): String =
    listOf(exam.acadYearSemester, exam.subjectName, exam.examDate, exam.startTime, index.toString()).joinToString("|")

private fun packSemesterOptions(pack: SharePack): List<String> {
    val fromPack = pack.semesters.map { it.acadYearSemester } +
        pack.courses.map { it.acadYearSemester } +
        pack.exams.map { it.acadYearSemester }
    val current = pack.semesters.firstOrNull { it.isCurrent }?.acadYearSemester
        ?: fromPack.maxOrNull()
        ?: SemesterRange.guessCurrent()
    return (SemesterRange.span(current, before = 8, after = 8) + fromPack).distinct()
}

private fun pickPackSemester(pack: SharePack, options: List<String>): String {
    val current = pack.semesters.firstOrNull { it.isCurrent }?.acadYearSemester
    if (current != null && pack.courses.any { it.acadYearSemester == current }) return current
    return pack.courses.groupingBy { it.acadYearSemester }.eachCount().maxByOrNull { it.value }?.key
        ?: options.firstOrNull().orEmpty()
}

private fun pickPackExamSemester(pack: SharePack, options: List<String>): String {
    val current = pack.semesters.firstOrNull { it.isCurrent }?.acadYearSemester
    if (current != null && pack.exams.any { it.acadYearSemester == current }) return current
    return pack.exams.groupingBy { it.acadYearSemester }.eachCount().maxByOrNull { it.value }?.key
        ?: pickPackSemester(pack, options)
}

private fun packMaxWeek(
    weeks: List<cn.sysu.kcb.data.local.WeekEntity>,
    courses: List<CourseEntity>,
): Int {
    val fromWeeks = weeks.maxOfOrNull { it.weekly } ?: 0
    var fromCourses = 0
    for (course in courses) {
        for (week in 1..WeekMask.MAX_WEEK) {
            if (WeekMask.has(course.weeksMask, week)) fromCourses = max(fromCourses, week)
        }
    }
    return max(fromWeeks, fromCourses).coerceAtLeast(1)
}
