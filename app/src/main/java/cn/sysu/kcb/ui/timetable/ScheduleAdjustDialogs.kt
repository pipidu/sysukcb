package cn.sysu.kcb.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.CoursePatchEntity
import cn.sysu.kcb.data.local.DayMoveEntity
import cn.sysu.kcb.data.local.DaySuspensionEntity
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.domain.courseAdjustKey
import cn.sysu.kcb.domain.weekdayLabel
import cn.sysu.kcb.ui.theme.KcbFilterChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SuspendClassesDialog(
    initialWeek: Int,
    maxWeek: Int,
    suspensions: List<DaySuspensionEntity>,
    onSave: (week: Int, days: Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val weekMax = maxWeek.coerceIn(1, WeekMask.MAX_WEEK)
    var week by remember { mutableIntStateOf(initialWeek.coerceIn(1, weekMax)) }
    var days by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(week, suspensions) {
        days = suspensions.filter { it.weekNo == week }.map { it.dayOfWeek }.toSet()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一键停课") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("勾选要停课的日子。停课后卡片变灰，也不再提醒。")
                WeekStepper(week, weekMax) { week = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (day in 1..7) {
                        KcbFilterChip(
                            selected = day in days,
                            onClick = { days = if (day in days) days - day else days + day },
                            label = { Text(weekdayLabel(day)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(week, days) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdjustClassesDialog(
    semester: String,
    initialWeek: Int,
    maxWeek: Int,
    courses: List<CourseEntity>,
    moves: List<DayMoveEntity>,
    patches: List<CoursePatchEntity>,
    onMove: (fromWeek: Int, fromDay: Int, toWeek: Int, toDay: Int) -> Unit,
    onClearMove: (fromWeek: Int, fromDay: Int) -> Unit,
    onSavePatch: (CoursePatchEntity) -> Unit,
    onClearPatch: (courseKey: String, week: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val weekMax = maxWeek.coerceIn(1, WeekMask.MAX_WEEK)
    var mode by remember { mutableIntStateOf(0) }
    var fromWeek by remember { mutableIntStateOf(initialWeek.coerceIn(1, weekMax)) }
    var toWeek by remember { mutableIntStateOf(initialWeek.coerceIn(1, weekMax)) }
    var fromDay by remember { mutableIntStateOf(1) }
    var toDay by remember { mutableIntStateOf(2) }
    var editWeek by remember { mutableIntStateOf(initialWeek.coerceIn(1, weekMax)) }
    var pickedKey by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var editDay by remember { mutableIntStateOf(1) }
    var startPeriod by remember { mutableIntStateOf(1) }
    var endPeriod by remember { mutableIntStateOf(2) }
    val weekCourses = courses
        .filter { WeekMask.has(it.weeksMask, editWeek) }
        .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.courseName }))
    val picked = weekCourses.firstOrNull { courseAdjustKey(it) == pickedKey }
    LaunchedEffect(pickedKey, editWeek, patches) {
        val course = weekCourses.firstOrNull { courseAdjustKey(it) == pickedKey } ?: return@LaunchedEffect
        val patch = patches.firstOrNull { it.weekNo == editWeek && it.courseKey == courseAdjustKey(course) }
        name = patch?.courseName ?: course.courseName
        teacher = patch?.teacher ?: course.teacher
        place = patch?.place ?: course.place
        notes = patch?.notes ?: course.notes
        editDay = patch?.dayOfWeek ?: course.dayOfWeek
        startPeriod = patch?.startPeriod ?: course.startPeriod
        endPeriod = patch?.endPeriod ?: course.endPeriod
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一键调课") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KcbFilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("挪到另一天") })
                    KcbFilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("改某一节") })
                }
                if (mode == 0) {
                    Text("原位置的课会留下并变灰，提醒改到新的那天。")
                    Text("从", fontWeight = FontWeight.Medium)
                    WeekStepper(fromWeek, weekMax) { fromWeek = it }
                    DayChips(fromDay) { fromDay = it }
                    Text("调到", fontWeight = FontWeight.Medium)
                    WeekStepper(toWeek, weekMax) { toWeek = it }
                    DayChips(toDay) { toDay = it }
                    moves.forEach { move ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "第${move.fromWeek}周${weekdayLabel(move.fromDay)} → 第${move.toWeek}周${weekdayLabel(move.toDay)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { onClearMove(move.fromWeek, move.fromDay) }) { Text("撤销") }
                        }
                    }
                } else {
                    Text("改过的名称、教师、地点、时间和备注会高亮。改了星期或节次时，原位置留下灰色卡片。")
                    WeekStepper(editWeek, weekMax) { editWeek = it }
                    if (weekCourses.isEmpty()) {
                        Text("这一周没有课")
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            weekCourses.forEach { course ->
                                val key = courseAdjustKey(course)
                                KcbFilterChip(
                                    selected = key == pickedKey,
                                    onClick = { pickedKey = key },
                                    label = { Text("${weekdayLabel(course.dayOfWeek)} ${course.startPeriod} ${course.courseName}") },
                                )
                            }
                        }
                    }
                    if (picked != null) {
                        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("课程名") }, singleLine = true)
                        OutlinedTextField(teacher, { teacher = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("教师") }, singleLine = true)
                        OutlinedTextField(place, { place = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("地点") }, singleLine = true)
                        OutlinedTextField(notes, { notes = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("备注") }, singleLine = true)
                        Text("星期")
                        DayChips(editDay) { editDay = it }
                        Text("节次 ${startPeriod}-${endPeriod}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KcbFilterChip(selected = false, onClick = { if (startPeriod > 1) startPeriod -= 1 }, label = { Text("开始-") })
                            KcbFilterChip(selected = false, onClick = { if (startPeriod < 20) startPeriod += 1 }, label = { Text("开始+") })
                            KcbFilterChip(selected = false, onClick = { if (endPeriod > 1) endPeriod -= 1 }, label = { Text("结束-") })
                            KcbFilterChip(selected = false, onClick = { if (endPeriod < 20) endPeriod += 1 }, label = { Text("结束+") })
                        }
                        val key = courseAdjustKey(picked)
                        if (patches.any { it.weekNo == editWeek && it.courseKey == key }) {
                            TextButton(onClick = { onClearPatch(key, editWeek) }) { Text("撤销这节课的修改") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (mode == 0) {
                        if (fromWeek != toWeek || fromDay != toDay) onMove(fromWeek, fromDay, toWeek, toDay)
                    } else {
                        val course = picked ?: return@TextButton
                        val start = minOf(startPeriod, endPeriod)
                        val end = maxOf(startPeriod, endPeriod)
                        onSavePatch(
                            CoursePatchEntity(
                                acadYearSemester = semester,
                                courseKey = courseAdjustKey(course),
                                weekNo = editWeek,
                                courseName = name.takeIf { it != course.courseName },
                                teacher = teacher.takeIf { it != course.teacher },
                                place = place.takeIf { it != course.place },
                                notes = notes.takeIf { it != course.notes },
                                dayOfWeek = editDay.takeIf { it != course.dayOfWeek },
                                startPeriod = start.takeIf { it != course.startPeriod },
                                endPeriod = end.takeIf { it != course.endPeriod },
                            ),
                        )
                    }
                },
                enabled = if (mode == 0) fromWeek != toWeek || fromDay != toDay else picked != null,
            ) { Text(if (mode == 0) "调课" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun WeekStepper(week: Int, maxWeek: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (week > 1) onChange(week - 1) }, enabled = week > 1) { Text("上一周") }
        Text("第${week}周", modifier = Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Medium)
        TextButton(onClick = { if (week < maxWeek) onChange(week + 1) }, enabled = week < maxWeek) { Text("下一周") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayChips(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (day in 1..7) {
            KcbFilterChip(
                selected = day == selected,
                onClick = { onSelect(day) },
                label = { Text(weekdayLabel(day)) },
            )
        }
    }
}
