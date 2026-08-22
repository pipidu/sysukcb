package cn.sysu.kcb.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.repo.TimetableSnapshot
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.notify.ClassAlarmScheduler
import cn.sysu.kcb.ui.AppViewModel
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
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val repo = KcbApp.instance.container.timetable
    val semesters by repo.semesters.collectAsStateWithLifecycle(emptyList())
    var snapshot by remember { mutableStateOf(TimetableSnapshot(null, emptyList(), emptyList(), emptyList())) }
    val semester = settings.selectedSemester.ifBlank { semesters.firstOrNull()?.acadYearSemester.orEmpty() }
    LaunchedEffect(semester) {
        if (semester.isBlank()) {
            snapshot = TimetableSnapshot(null, emptyList(), emptyList(), emptyList())
            return@LaunchedEffect
        }
        repo.timetableState(semester).collectLatest { snapshot = it }
    }
    var selectedWeek by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(snapshot.weeks) {
        if (selectedWeek == 0) {
            selectedWeek = ClassAlarmScheduler.resolveWeek(LocalDate.now(), snapshot.weeks)
                ?: snapshot.weeks.firstOrNull()?.weekly
                ?: 1
        }
    }
    val week = snapshot.weeks.firstOrNull { it.weekly == selectedWeek }
    var semesterMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("课表", fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(semester.ifBlank { null }, week?.weeklyName ?: "第${selectedWeek}周").joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { semesterMenu = true }) { Text(semester.ifBlank { "学年" }) }
                        DropdownMenu(expanded = semesterMenu, onDismissRequest = { semesterMenu = false }) {
                            if (semesters.isEmpty()) {
                                DropdownMenuItem(text = { Text("暂无学期数据") }, onClick = { semesterMenu = false })
                            }
                            semesters.forEach {
                                DropdownMenuItem(
                                    text = { Text(it.acadYearSemester) },
                                    onClick = {
                                        viewModel.setSemester(it.acadYearSemester)
                                        semesterMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { selectedWeek = (selectedWeek - 1).coerceAtLeast(1) }) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一周")
                    }
                    IconButton(onClick = { selectedWeek = selectedWeek + 1 }) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "下一周")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(LocalDate.now().dayOfWeek.value, 1, semester) }) {
                Icon(Icons.Default.Add, contentDescription = "添加课程")
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            if (importing) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (snapshot.courses.isEmpty() && snapshot.weeks.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("还没有课表", style = MaterialTheme.typography.titleMedium)
                    Text("登录中山大学教务系统后即可自动导入", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                    TextButton(onClick = onLogin) { Text("去登录") }
                }
            } else {
                TimetableGrid(
                    periods = snapshot.periods,
                    courses = snapshot.courses.filter { WeekMask.has(it.weeksMask, selectedWeek) },
                    onCourse = onEdit,
                    onEmpty = { day, period -> onAdd(day, period, semester) },
                )
            }
        }
    }
}

@Composable
private fun TimetableGrid(
    periods: List<PeriodEntity>,
    courses: List<CourseEntity>,
    onCourse: (Long) -> Unit,
    onEmpty: (Int, Int) -> Unit,
) {
    val periodH = 64.dp
    val headerH = 36.dp
    val timeW = 42.dp
    val rows = periods.ifEmpty { (1..11).map { PeriodEntity(sectionNumber = it, acadYearSemester = "", minorName = "第${it}节", startTime = "", endTime = "", bigSection = "", bigSectionName = "") } }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll).padding(8.dp)) {
        BoxWithConstraints(Modifier.width(timeW + 72.dp * 7)) {
            val colW = (maxWidth - timeW) / 7
            val gridH = headerH + periodH * rows.size
            Box(Modifier.height(gridH).width(maxWidth)) {
                Row(Modifier.fillMaxWidth().height(headerH), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(timeW))
                    dayNames.forEachIndexed { index, name ->
                        val today = LocalDate.now().dayOfWeek.value == index + 1
                        Text(
                            name,
                            modifier = Modifier.width(colW),
                            textAlign = TextAlign.Center,
                            fontWeight = if (today) FontWeight.Bold else FontWeight.Medium,
                            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
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
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("${period.sectionNumber}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            if (period.startTime.isNotBlank()) {
                                Text(period.startTime, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                        }
                        for (day in 1..7) {
                            Box(
                                Modifier
                                    .width(colW)
                                    .height(periodH)
                                    .border(0.4.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                    .clickable { onEmpty(day, period.sectionNumber) },
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
                                start = timeW + colW * (course.dayOfWeek - 1) + 2.dp,
                                top = headerH + periodH * startIndex + 2.dp,
                            )
                            .size(width = colW - 4.dp, height = periodH * span - 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(course.color).copy(alpha = 0.92f))
                            .clickable { onCourse(course.id) }
                            .padding(4.dp),
                    ) {
                        Column {
                            Text(
                                course.courseName,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (course.place.isNotBlank()) {
                                Text(
                                    course.place,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 9.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
