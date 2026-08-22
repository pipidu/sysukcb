package cn.sysu.kcb.ui.timetable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import cn.sysu.kcb.domain.SemesterRange
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.notify.ClassAlarmScheduler
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.course.CourseDetailSheet
import cn.sysu.kcb.ui.theme.KcbMotion
import kotlinx.coroutines.flow.collectLatest
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
    val semesterOptions = remember(semesterAnchor, semesters) {
        (SemesterRange.span(semesterAnchor, before = 8, after = 8) + semesters.map { it.acadYearSemester }).distinct()
    }
    val semester = remember(settings.selectedSemester, semesters, semesterOptions) {
        pickSemester(settings, semesters, semesterOptions)
    }
    LaunchedEffect(semester) {
        if (semester.isNotBlank() && semester != settings.selectedSemester) {
            viewModel.setSemester(semester)
        }
        if (semester.isBlank()) {
            snapshot = TimetableSnapshot(null, emptyList(), emptyList(), emptyList())
            return@LaunchedEffect
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
    val week = snapshot.weeks.firstOrNull { it.weekly == selectedWeek }
    var semesterMenu by remember { mutableStateOf(false) }
    var weekPicker by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var viewingCourse by remember { mutableStateOf<CourseEntity?>(null) }
    val maxWeek = snapshot.weeks.maxOfOrNull { it.weekly } ?: 30
    val academicWeek = remember(snapshot.weeks, snapshot.semester?.startMillis) {
        ClassAlarmScheduler.resolveWeek(
            date = LocalDate.now(),
            weeks = snapshot.weeks,
            semesterStartMillis = snapshot.semester?.startMillis ?: 0L,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                title = {
                    Row(
                        modifier = Modifier.clickable { weekPicker = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            AnimatedContent(
                                targetState = week?.weeklyName?.ifBlank { null } ?: "第${selectedWeek}周",
                                label = "weekTitle",
                            ) { label ->
                                Text(
                                    label,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 26.sp,
                                )
                            }
                            Text(
                                semester.ifBlank { "课表" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                            )
                        }
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "选择周次",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { semesterMenu = true }) {
                            Text(semester.ifBlank { "学年" }, color = MaterialTheme.colorScheme.onPrimary)
                        }
                        DropdownMenu(expanded = semesterMenu, onDismissRequest = { semesterMenu = false }) {
                            semesterOptions.forEach { option ->
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
                        }
                    }
                    IconButton(onClick = {
                        userPickedWeek = true
                        selectedWeek = (selectedWeek - 1).coerceAtLeast(1)
                    }) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一周")
                    }
                    IconButton(onClick = {
                        userPickedWeek = true
                        selectedWeek = (selectedWeek + 1).coerceAtMost(maxWeek)
                    }) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "下一周")
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
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
                },
            )
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
                    val empty = snapshot.courses.isEmpty() && snapshot.weeks.isEmpty() && snapshot.periods.isEmpty() && !editing
                    AnimatedContent(
                        targetState = empty,
                        label = "emptyOrGrid",
                    ) { isEmpty ->
                        if (isEmpty) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            ) {
                                Text("还没有课表", style = MaterialTheme.typography.titleMedium)
                                Text("登录中山大学教务系统后即可自动导入，或打开右上角菜单添加课程", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                                TextButton(onClick = onLogin) { Text("去登录") }
                            }
                        } else {
                            AnimatedContent(
                                targetState = selectedWeek,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    if (initialState == 0 || targetState == 0) {
                                        fadeIn() togetherWith fadeOut()
                                    } else {
                                        KcbMotion.weekPage(targetState > initialState)
                                    }
                                },
                                label = "weekPage",
                            ) { weekNo ->
                                val weekEntity = snapshot.weeks.firstOrNull { it.weekly == weekNo }
                                TimetableGrid(
                                    periods = snapshot.periods,
                                    courses = snapshot.courses.filter { WeekMask.has(it.weeksMask, weekNo) },
                                    weekStart = resolveWeekStart(weekNo, weekEntity, snapshot.weeks, snapshot.semester?.startMillis ?: 0L),
                                    editing = editing,
                                    onCourse = { course ->
                                        if (editing) onEdit(course.id) else viewingCourse = course
                                    },
                                    onEmpty = if (editing) {
                                        { day, period -> onAdd(day, period, semester) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
            viewingCourse?.let { course ->
                CourseDetailSheet(
                    course = course,
                    periods = snapshot.periods,
                    onDismiss = { viewingCourse = null },
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
            onPick = { weekNo ->
                userPickedWeek = true
                selectedWeek = weekNo
                weekPicker = false
            },
            onDismiss = { weekPicker = false },
        )
    }
}

private fun pickSemester(settings: UserSettings, semesters: List<SemesterEntity>, options: List<String>): String {
    val saved = settings.selectedSemester
    if (saved.isNotBlank() && (options.contains(saved) || semesters.any { it.acadYearSemester == saved })) return saved
    return semesters.firstOrNull { it.isCurrent }?.acadYearSemester
        ?: options.firstOrNull().orEmpty()
        ?: saved
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekPickerDialog(
    weeks: List<WeekEntity>,
    selectedWeek: Int,
    currentWeek: Int?,
    maxWeek: Int,
    onPick: (Int) -> Unit,
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
                    for (weekNo in 1..last) {
                        val isCurrent = weekNo == currentWeek
                        FilterChip(
                            selected = weekNo == selectedWeek,
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

private fun resolveWeekStart(
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
    return mondayOf(LocalDate.now())
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
private fun TimetableGrid(
    periods: List<PeriodEntity>,
    courses: List<CourseEntity>,
    weekStart: LocalDate?,
    editing: Boolean,
    onCourse: (CourseEntity) -> Unit,
    onEmpty: ((Int, Int) -> Unit)?,
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
    val vScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(vScroll)) {
        val colW = (maxWidth - timeW) / 7
        val gridH = headerH + periodH * rows.size
        Box(Modifier.height(gridH).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(headerH), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.width(timeW).height(headerH),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${(weekStart ?: today).monthValue}月",
                        style = compactPeriod,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                dayNames.forEachIndexed { index, name ->
                    val date = weekStart?.plusDays(index.toLong())
                    val isToday = date == today
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
                            date?.dayOfMonth?.toString() ?: "",
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
            courses.forEach { course ->
                val startIndex = rows.indexOfFirst { it.sectionNumber == course.startPeriod }.takeIf { it >= 0 }
                    ?: (course.startPeriod - 1)
                val span = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
                Box(
                    Modifier
                        .padding(
                            start = timeW + colW * (course.dayOfWeek - 1) + 1.dp,
                            top = headerH + periodH * startIndex + 1.dp,
                        )
                        .size(width = colW - 2.dp, height = periodH * span - 2.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(course.color).copy(alpha = 0.94f))
                        .clickable { onCourse(course) }
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
                    }
                }
            }
        }
    }
}

private fun displayPlace(place: String): String =
    place.replace(Regex("（\\d+座）|\\(\\d+座\\)"), "").replace("/", "\n").trim()
