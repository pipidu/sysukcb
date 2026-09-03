package cn.sysu.kcb.ui.exam

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import cn.sysu.kcb.ui.theme.KcbTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.domain.SemesterRange
import cn.sysu.kcb.ui.AppViewModel
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val repo = KcbApp.instance.container.timetable
    val semesters by repo.semesters.collectAsStateWithLifecycle(emptyList())
    val examSemesterIds by repo.populatedExamSemesters.collectAsStateWithLifecycle(emptyList())
    val currentAnchor = remember(settings.selectedSemester, semesters) {
        semesters.firstOrNull { it.isCurrent }?.acadYearSemester
            ?: settings.selectedSemester.takeIf { it.isNotBlank() }
            ?: SemesterRange.guessCurrent()
    }
    val semesterOptions = remember(examSemesterIds) {
        examSemesterIds.distinct()
            .sortedByDescending { SemesterRange.ordinal(it) ?: Int.MIN_VALUE }
    }
    var examSemester by rememberSaveable { mutableStateOf("") }
    var userPickedSemester by rememberSaveable { mutableStateOf(false) }
    var selectedWeekId by rememberSaveable { mutableStateOf("all") }
    var semesterMenu by remember { mutableStateOf(false) }

    LaunchedEffect(settings.selectedSemester, semesters, examSemesterIds, userPickedSemester, semesterOptions) {
        if (userPickedSemester && examSemester.isNotBlank()) return@LaunchedEffect
        val current = settings.selectedSemester.takeIf { it.isNotBlank() }
            ?: semesters.firstOrNull { it.isCurrent }?.acadYearSemester
            ?: currentAnchor
        examSemester = when {
            current.isNotBlank() && current in examSemesterIds -> current
            semesterOptions.isNotEmpty() -> semesterOptions.first()
            else -> current
        }
    }

    val examWeeks by repo.examWeeks(examSemester).collectAsStateWithLifecycle(emptyList())
    val exams by repo.exams(examSemester).collectAsStateWithLifecycle(emptyList())

    LaunchedEffect(examSemester) {
        selectedWeekId = "all"
    }

    val visible = exams.filter { selectedWeekId == "all" || it.examWeekId == selectedWeekId }
    val otherSemesterWithExams = examSemesterIds.firstOrNull { it != examSemester }

    Scaffold(
        topBar = {
            KcbTopBar(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    "考试",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                )
                Box {
                    TextButton(onClick = { semesterMenu = true }) {
                        Text(examSemester.ifBlank { "学年" }, color = MaterialTheme.colorScheme.onPrimary)
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
                                    userPickedSemester = true
                                    examSemester = option
                                    selectedWeekId = "all"
                                    semesterMenu = false
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
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
                    Text(
                        when {
                            examWeeks.isEmpty() -> "暂无考试，请到「我的」导入课表和考试"
                            otherSemesterWithExams != null -> "该学期暂无考试，可切换到 $otherSemesterWithExams"
                            else -> "该考试周暂无安排"
                        },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.id }) { exam ->
                        Card(Modifier.fillMaxWidth().animateItem()) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
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
                                ExamCountdown(exam.examDate)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shortWeekName(name: String): String = when {
    name.contains("缓补") -> "缓补考"
    name.contains("结课") -> "结课考试"
    name.contains("期末") -> "期末考"
    else -> name
}

@Composable
private fun ExamCountdown(examDate: String) {
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
