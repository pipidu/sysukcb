package cn.sysu.kcb.ui.timetable

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
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
import cn.sysu.kcb.ui.theme.KcbTopBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.prefs.UserSettings
import cn.sysu.kcb.data.repo.TimetableSnapshot
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.SemesterRange
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.notify.ClassAlarmScheduler
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.course.CourseDetailSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate

private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    viewModel: AppViewModel,
    onEdit: (Long) -> Unit,
    onAdd: (Int, Int, String) -> Unit,
    onLogin: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val repo = KcbApp.instance.container.timetable
    val semesters by repo.semesters.collectAsStateWithLifecycle(emptyList())
    var snapshot by remember { mutableStateOf(TimetableSnapshot(null, emptyList(), emptyList(), emptyList())) }
    val semesterAnchor = remember(settings.selectedSemester, semesters) {
        semesters.firstOrNull { it.isCurrent }?.acadYearSemester
            ?: settings.selectedSemester.takeIf { it.isNotBlank() }
            ?: SemesterRange.guessCurrent()
    }
    val populatedIds by repo.populatedCourseSemesters.collectAsStateWithLifecycle(emptyList())
    val populatedSemesters = remember(populatedIds) {
        populatedIds.distinct().sortedByDescending { SemesterRange.ordinal(it) ?: Int.MIN_VALUE }
    }
    val addableSemesters = remember(semesterAnchor, populatedSemesters) {
        SemesterRange.span(semesterAnchor, before = 8, after = 8)
            .filterNot { it in populatedSemesters }
    }
    val semester = remember(settings.selectedSemester, semesters, populatedSemesters) {
        pickSemester(settings, semesters, populatedSemesters)
    }
    LaunchedEffect(semester, settings.selectedSemester, semesters) {
        if (semester.isBlank()) {
            snapshot = TimetableSnapshot(null, emptyList(), emptyList(), emptyList())
            return@LaunchedEffect
        }
        // Only persist a semester that actually exists in the DB. Cold start used to
        // write a guessed empty future term over the saved selection, so the grid
        // looked like imported data had vanished.
        if (settings.selectedSemester.isBlank() &&
            semesters.any { it.acadYearSemester == semester }
        ) {
            viewModel.setSemester(semester)
        }
        repo.timetableState(semester).collectLatest { snapshot = it }
    }
    var selectedWeek by rememberSaveable { mutableIntStateOf(0) }
    var userPickedWeek by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(semester) { userPickedWeek = false }
    LaunchedEffect(semester, snapshot.weeks, snapshot.semester?.startMillis, userPickedWeek) {
        if (!userPickedWeek) {
            selectedWeek = ClassAlarmScheduler.resolveWeek(
                date = LocalDate.now(),
                weeks = snapshot.weeks,
                semesterStartMillis = snapshot.semester?.startMillis ?: 0L,
            ) ?: snapshot.weeks.firstOrNull { it.weekly > 0 }?.weekly ?: 1
        }
    }
    var semesterMenu by remember { mutableStateOf(false) }
    var addSemesterOpen by remember { mutableStateOf(false) }
    var weekPicker by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var termOverview by rememberSaveable { mutableStateOf(false) }
    var viewingCourses by remember { mutableStateOf<List<CourseEntity>?>(null) }
    val maxWeek = snapshot.weeks.maxOfOrNull { it.weekly } ?: 30
    val academicWeek = remember(snapshot.weeks, snapshot.semester?.startMillis) {
        ClassAlarmScheduler.resolveWeek(
            date = LocalDate.now(),
            weeks = snapshot.weeks,
            semesterStartMillis = snapshot.semester?.startMillis ?: 0L,
        )
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
            .collect { page ->
                if (syncingPager || selectedWeek <= 0) return@collect
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
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            }.getOrNull()?.let { viewModel.importJson(it) }
        }
    }

    Scaffold(
        topBar = {
            KcbTopBar(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { weekPicker = true }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        if (termOverview) {
                            Text(
                                "学期课表",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 22.sp,
                            )
                            Text(
                                "全部周次",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                            )
                        } else {
                            WeekTitleText(
                                pagerState = pagerState,
                                weeks = snapshot.weeks,
                                selectedWeek = selectedWeek,
                                syncingPager = syncingPager,
                            )
                            Text(
                                weekRangeLabel(
                                    weekNo = displayedWeekNo(pagerState, selectedWeek, syncingPager),
                                    weeks = snapshot.weeks,
                                    semesterStartMillis = snapshot.semester?.startMillis ?: 0L,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "选择周次",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Box {
                    TextButton(onClick = { semesterMenu = true }) {
                        Text(semester.ifBlank { "学年" }, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    DropdownMenu(expanded = semesterMenu, onDismissRequest = { semesterMenu = false }) {
                        populatedSemesters.forEach { option ->
                            val entity = semesters.firstOrNull { it.acadYearSemester == option }
                            DropdownMenuItem(
                                text = { Text(buildString {
                                    append(option)
                                    if (entity?.isCurrent == true) append("（当前）")
                                }) },
                                onClick = {
                                    userPickedWeek = false
                                    viewModel.setSemester(option)
                                    semesterMenu = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("添加学年") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                semesterMenu = false
                                addSemesterOpen = true
                            },
                        )
                    }
                }
                Box {
                    IconButton(onClick = { moreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (termOverview) "查看周课表" else "学期总课表") },
                                onClick = {
                                    termOverview = !termOverview
                                    moreMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("添加课程") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    moreMenu = false
                                    editing = true
                                    onAdd(LocalDate.now().dayOfWeek.value, 1, semester)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (editing) "退出编辑模式" else "进入编辑模式") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    editing = !editing
                                    moreMenu = false
                                },
                            )
                        }
                    }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = editing,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "编辑模式：点击课程修改，点击空白格子添加",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        TextButton(onClick = { editing = false }) { Text("完成") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    val empty = snapshot.courses.isEmpty() && !editing
                    if (termOverview) {
                        TimetableGrid(
                            periods = snapshot.periods,
                            courses = snapshot.courses,
                            weekStart = resolveWeekStart(
                                academicWeek ?: selectedWeek,
                                snapshot.weeks.firstOrNull { it.weekly == (academicWeek ?: selectedWeek) },
                                snapshot.weeks,
                                snapshot.semester?.startMillis ?: 0L,
                            ),
                            onCourses = { group ->
                                if (editing && group.size == 1) onEdit(group.first().id)
                                else viewingCourses = group
                            },
                            onEmpty = if (editing) {
                                { day, period -> onAdd(day, period, semester) }
                            } else {
                                null
                            },
                            themeColor = settings.themeColor,
                        )
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                        ) { page ->
                            val weekNo = page + 1
                            val weekEntity = snapshot.weeks.firstOrNull { it.weekly == weekNo }
                            TimetableGrid(
                                periods = snapshot.periods,
                                courses = snapshot.courses.filter { WeekMask.has(it.weeksMask, weekNo) },
                                weekStart = resolveWeekStart(weekNo, weekEntity, snapshot.weeks, snapshot.semester?.startMillis ?: 0L),
                                onCourses = { group ->
                                    if (editing && group.size == 1) onEdit(group.first().id)
                                    else viewingCourses = group
                                },
                                onEmpty = if (editing) {
                                    { day, period -> onAdd(day, period, semester) }
                                } else {
                                    null
                                },
                                themeColor = settings.themeColor,
                            )
                        }
                    }
                    if (empty) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                        ) {
                            Text("还没有课表", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onLogin) { Text("去登录") }
                                TextButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                                    Text("从文件导入")
                                }
                            }
                        }
                    }
                }
            }
            viewingCourses?.let { courses ->
                CourseDetailSheet(
                    courses = courses,
                    periods = snapshot.periods,
                    themeColor = settings.themeColor,
                    onDismiss = { viewingCourses = null },
                    onEdit = { course ->
                        viewingCourses = null
                        onEdit(course.id)
                    },
                    bottomInset = sheetBottomInset,
                )
            }
        }
    }
    if (weekPicker) {
        WeekPickerDialog(
            weeks = snapshot.weeks,
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
    if (addSemesterOpen) {
        AddSemesterDialog(
            candidates = addableSemesters,
            onPick = { picked ->
                userPickedWeek = false
                viewModel.addSemester(picked)
                addSemesterOpen = false
            },
            onDismiss = { addSemesterOpen = false },
        )
    }
}

@Composable
internal fun WeekTitleText(
    pagerState: PagerState,
    weeks: List<WeekEntity>,
    selectedWeek: Int,
    syncingPager: Boolean,
) {
    val displayWeek = displayedWeekNo(pagerState, selectedWeek, syncingPager)
    val label = weeks.firstOrNull { it.weekly == displayWeek }?.weeklyName?.ifBlank { null }
        ?: "第${displayWeek}周"
    Text(
        label,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 22.sp,
    )
}

internal fun displayedWeekNo(
    pagerState: PagerState,
    selectedWeek: Int,
    syncingPager: Boolean,
): Int {
    val currentPage = pagerState.currentPage
    val offset = pagerState.currentPageOffsetFraction
    val settled = pagerState.settledPage
    return if (syncingPager) {
        selectedWeek.coerceAtLeast(1)
    } else {
        val pos = currentPage + offset
        val page = when {
            pos > settled + 0.01f -> settled + 1
            pos < settled - 0.01f -> settled - 1
            else -> settled
        }
        (page + 1).coerceIn(1, pagerState.pageCount.coerceAtLeast(1))
    }
}

internal fun weekRangeLabel(
    weekNo: Int,
    weeks: List<WeekEntity>,
    semesterStartMillis: Long,
): String {
    val start = resolveWeekStart(
        weekNo,
        weeks.firstOrNull { it.weekly == weekNo },
        weeks,
        semesterStartMillis,
    ) ?: return ""
    val end = start.plusDays(6)
    return "${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
}

private fun pickSemester(
    settings: UserSettings,
    semesters: List<SemesterEntity>,
    populated: List<String>,
): String {
    val saved = settings.selectedSemester
    if (saved.isNotBlank()) return saved
    return semesters.firstOrNull { it.isCurrent && it.acadYearSemester in populated }?.acadYearSemester
        ?: populated.firstOrNull().orEmpty()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddSemesterDialog(
    candidates: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加学年") },
        text = {
            if (candidates.isEmpty()) {
                Text("前后各 8 个学期都已在列表里。")
            } else {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    candidates.forEach { option ->
                        FilterChip(
                            selected = false,
                            onClick = { onPick(option) },
                            label = { Text(option, fontSize = 13.sp, lineHeight = 16.sp) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeekPickerDialog(
    weeks: List<WeekEntity>,
    selectedWeek: Int,
    currentWeek: Int?,
    maxWeek: Int,
    termOverview: Boolean,
    onPick: (Int) -> Unit,
    onPickAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val last = maxOf(maxWeek, weeks.maxOfOrNull { it.weekly } ?: 0, selectedWeek).coerceAtLeast(1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择周次") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = termOverview,
                        onClick = onPickAll,
                        label = {
                            Text("全部", fontSize = 13.sp, lineHeight = 16.sp)
                        },
                    )
                    for (weekNo in 1..last) {
                        val isCurrent = weekNo == currentWeek
                        FilterChip(
                            selected = !termOverview && weekNo == selectedWeek,
                            onClick = { onPick(weekNo) },
                            label = {
                                Text(
                                    if (isCurrent) "${weekNo} 本周" else "$weekNo",
                                    fontSize = 13.sp,
                                    lineHeight = 16.sp,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

internal fun resolveWeekStart(
    selectedWeek: Int,
    week: WeekEntity?,
    weeks: List<WeekEntity>,
    semesterStartMillis: Long,
): LocalDate? {
    week?.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { return mondayOf(it) }
    val dated = weeks.mapNotNull { item ->
        val start = item.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        item.weekly to mondayOf(start)
    }
    dated.minByOrNull { it.first }?.let { (weekly, start) ->
        return start.plusWeeks((selectedWeek - weekly).toLong())
    }
    if (semesterStartMillis > 0) {
        val start = java.time.Instant.ofEpochMilli(semesterStartMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return mondayOf(start).plusWeeks((selectedWeek - 1).coerceAtLeast(0).toLong())
    }
    val todayMonday = mondayOf(LocalDate.now())
    val currentNo = ClassAlarmScheduler.resolveWeek(LocalDate.now(), weeks, semesterStartMillis) ?: 1
    return todayMonday.plusWeeks((selectedWeek - currentNo).toLong())
}

private fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

private val compactName = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.SemiBold,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)
private val compactMeta = TextStyle(
    fontSize = 8.sp,
    lineHeight = 10.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)
private val compactPeriod = TextStyle(
    fontSize = 11.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.Medium,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)
private val compactTime = TextStyle(
    fontSize = 8.sp,
    lineHeight = 9.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

@Composable
internal fun TimetableGrid(
    periods: List<PeriodEntity>,
    courses: List<CourseEntity>,
    weekStart: LocalDate?,
    onCourses: (List<CourseEntity>) -> Unit,
    onEmpty: ((Int, Int) -> Unit)?,
    themeColor: Long,
) {
    val periodH = 58.dp
    val headerH = 40.dp
    val timeW = 40.dp
    val today = LocalDate.now()
    val rows = periods.ifEmpty {
        (1..11).map {
            PeriodEntity(
                sectionNumber = it,
                acadYearSemester = "",
                minorName = "第${it}节",
                startTime = "",
                endTime = "",
                bigSection = "",
                bigSectionName = "",
            )
        }
    }
    val groups = remember(courses) { overlapGroups(courses) }
    val vScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(vScroll)) {
        val colW = (maxWidth - timeW) / 7
        val gridH = headerH + periodH * rows.size
        Box(Modifier.height(gridH).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(headerH), verticalAlignment = Alignment.CenterVertically) {
                val dayDates = (0..6).map { weekStart?.plusDays(it.toLong()) }
                val months = dayDates.mapNotNull { it?.monthValue }.distinct()
                val cornerMonth = when {
                    months.size >= 2 -> "${months.first()}/${months.last()}月"
                    months.size == 1 -> "${months.first()}月"
                    else -> "${(weekStart ?: today).monthValue}月"
                }
                Column(
                    Modifier.width(timeW).height(headerH),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        cornerMonth,
                        style = compactPeriod,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                dayNames.forEachIndexed { index, name ->
                    val date = dayDates[index]
                    val isToday = date == today
                    val dateLabel = when {
                        date == null -> ""
                        weekStart != null && date.monthValue != weekStart.monthValue ->
                            "${date.monthValue}/${date.dayOfMonth}"
                        else -> date.dayOfMonth.toString()
                    }
                    Column(
                        Modifier.width(colW).height(headerH),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            name,
                            textAlign = TextAlign.Center,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            dateLabel,
                            textAlign = TextAlign.Center,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                }
            }
            rows.forEachIndexed { index, period ->
                val y = headerH + periodH * index
                Row(
                    Modifier
                        .padding(top = y)
                        .height(periodH)
                        .fillMaxWidth(),
                ) {
                    Column(
                        Modifier.width(timeW).height(periodH),
                        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${period.sectionNumber}", style = compactPeriod)
                        if (period.startTime.isNotBlank()) {
                            Text(
                                period.startTime,
                                style = compactTime,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            )
                        }
                        if (period.endTime.isNotBlank()) {
                            Text(
                                period.endTime,
                                style = compactTime,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                    for (day in 1..7) {
                        Box(
                            Modifier
                                .width(colW)
                                .height(periodH)
                                .border(0.4.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                .then(
                                    if (onEmpty != null) Modifier.clickable { onEmpty(day, period.sectionNumber) }
                                    else Modifier,
                                ),
                        )
                    }
                }
            }
            groups.forEach { group ->
                val course = group.first()
                val cardColor = CourseColors.display(course.color, course.courseName, themeColor)
                val startPeriod = group.minOf { it.startPeriod }
                val endPeriod = group.maxOf { it.endPeriod }
                val startIndex = rows.indexOfFirst { it.sectionNumber == startPeriod }.takeIf { it >= 0 }
                    ?: (startPeriod - 1)
                val span = (endPeriod - startPeriod + 1).coerceAtLeast(1)
                Box(
                    Modifier
                        .padding(
                            start = timeW + colW * (course.dayOfWeek - 1) + 1.dp,
                            top = headerH + periodH * startIndex + 1.dp,
                        )
                        .size(width = colW - 2.dp, height = periodH * span - 2.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(cardColor))
                        .clickable { onCourses(group) }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text(
                            course.courseName,
                            color = Color.White,
                            style = compactName,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (course.teacher.isNotBlank()) {
                            Text(
                                course.teacher,
                                color = Color.White.copy(alpha = 0.92f),
                                style = compactMeta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (course.place.isNotBlank()) {
                            Text(
                                displayPlace(course.place),
                                color = Color.White.copy(alpha = 0.92f),
                                style = compactMeta,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (course.notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                course.notes,
                                color = Color.White.copy(alpha = 0.92f),
                                style = compactMeta,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (group.size > 1) {
                        Text(
                            "${group.size}",
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.Black.copy(alpha = 0.28f))
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                            color = Color.White,
                            fontSize = 8.sp,
                            lineHeight = 9.sp,
                            fontWeight = FontWeight.Bold,
                            style = compactMeta,
                        )
                    }
                }
            }
        }
    }
}

private fun overlapGroups(courses: List<CourseEntity>): List<List<CourseEntity>> {
    val result = mutableListOf<List<CourseEntity>>()
    for ((_, dayCourses) in courses.groupBy { it.dayOfWeek }) {
        val remaining = dayCourses
            .sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }, { it.courseName }, { it.id }))
            .toMutableList()
        while (remaining.isNotEmpty()) {
            val group = mutableListOf(remaining.removeAt(0))
            var changed = true
            while (changed) {
                changed = false
                val start = group.minOf { it.startPeriod }
                val end = group.maxOf { it.endPeriod }
                val hit = remaining.filter { it.startPeriod <= end && it.endPeriod >= start }
                if (hit.isNotEmpty()) {
                    remaining.removeAll(hit.toSet())
                    group += hit
                    changed = true
                }
            }
            result += group
        }
    }
    return result
}

private fun displayPlace(place: String): String =
    place.replace(Regex("（\\d+座）|\\(\\d+座\\)"), "").replace("/", "\n").trim()
