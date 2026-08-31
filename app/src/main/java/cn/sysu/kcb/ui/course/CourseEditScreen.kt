package cn.sysu.kcb.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import cn.sysu.kcb.ui.theme.KcbTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.ui.AppViewModel

private val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseEditScreen(
    viewModel: AppViewModel,
    courseId: Long?,
    presetDay: Int,
    presetPeriod: Int,
    semester: String,
    onDone: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val palette = remember(settings.themeColor) { CourseColors.paletteFor(settings.themeColor) }
    var loaded by remember { mutableStateOf<CourseEntity?>(null) }
    LaunchedEffect(courseId) {
        loaded = courseId?.let { viewModel.getCourse(it) }
    }
    val existing = loaded
    var name by remember(existing) { mutableStateOf(existing?.courseName.orEmpty()) }
    var teacher by remember(existing) { mutableStateOf(existing?.teacher.orEmpty()) }
    var place by remember(existing) { mutableStateOf(existing?.place.orEmpty()) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    var day by remember(existing) { mutableIntStateOf(existing?.dayOfWeek ?: presetDay.coerceIn(1, 7)) }
    var start by remember(existing) { mutableIntStateOf(existing?.startPeriod ?: presetPeriod.coerceIn(1, 11)) }
    var end by remember(existing) { mutableIntStateOf(existing?.endPeriod ?: presetPeriod.coerceIn(1, 11)) }
    var color by remember(existing, settings.themeColor) {
        mutableLongStateOf(existing?.color ?: CourseColors.of(name.ifBlank { "课" }, settings.themeColor))
    }
    var weeksMask by remember(existing) { mutableLongStateOf(existing?.weeksMask ?: WeekMask.fromRange(1, 18)) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            KcbTopBar {
                IconButton(onClick = onDone) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(if (existing == null) "添加课程" else "编辑课程")
            }
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it; if (existing == null) color = CourseColors.of(it.ifBlank { "课" }, settings.themeColor) }, label = { Text("课程名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(teacher, { teacher = it }, label = { Text("教师") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(place, { place = it }, label = { Text("地点") }, modifier = Modifier.fillMaxWidth())
            Text("星期")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEachIndexed { index, label ->
                    FilterChip(selected = day == index + 1, onClick = { day = index + 1 }, label = { Text(label) })
                }
            }
            Text("节次 $start-$end")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..11).forEach { p ->
                    FilterChip(
                        selected = p in start..end,
                        onClick = {
                            if (p <= start) {
                                start = p
                                if (end < start) end = start
                            } else {
                                end = p
                            }
                        },
                        label = { Text("$p") },
                    )
                }
            }
            Text("周次（${WeekMask.describe(weeksMask, 20)}）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = weeksMask == WeekMask.fromRange(1, 20),
                    onClick = { weeksMask = WeekMask.fromRange(1, 20) },
                    label = { Text("全选") },
                )
                FilterChip(
                    selected = weeksMask == WeekMask.fromRange(1, 20) { it % 2 == 1 },
                    onClick = { weeksMask = WeekMask.fromRange(1, 20) { it % 2 == 1 } },
                    label = { Text("单周") },
                )
                FilterChip(
                    selected = weeksMask == WeekMask.fromRange(1, 20) { it % 2 == 0 },
                    onClick = { weeksMask = WeekMask.fromRange(1, 20) { it % 2 == 0 } },
                    label = { Text("双周") },
                )
                FilterChip(
                    selected = weeksMask == 0L,
                    onClick = { weeksMask = 0L },
                    label = { Text("清空") },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(5) { col ->
                            val w = row * 5 + col + 1
                            val on = WeekMask.has(weeksMask, w)
                            val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .clickable {
                                        weeksMask = if (on) {
                                            weeksMask and WeekMask.bit(w).inv()
                                        } else {
                                            weeksMask or WeekMask.bit(w)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$w", color = fg, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            Text("颜色")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                palette.forEach { c ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(if (color == c) 2.dp else 0.dp, Color.Black, CircleShape)
                            .clickable { color = c },
                    )
                }
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    if (name.isBlank() || semester.isBlank()) return@Button
                    val entity = (existing ?: CourseEntity(
                        acadYearSemester = semester,
                        source = "manual",
                        courseName = name.trim(),
                        dayOfWeek = day,
                        startPeriod = start,
                        endPeriod = end.coerceAtLeast(start),
                        weeksMask = weeksMask,
                        color = color,
                    )).copy(
                        courseName = name.trim(),
                        teacher = teacher.trim(),
                        place = place.trim(),
                        notes = notes.trim(),
                        dayOfWeek = day,
                        startPeriod = minOf(start, end),
                        endPeriod = maxOf(start, end),
                        weeksMask = weeksMask,
                        color = color,
                        timeDetail = WeekMask.describe(weeksMask),
                        locallyEdited = existing?.source == "imported" || existing?.locallyEdited == true,
                        acadYearSemester = existing?.acadYearSemester ?: semester,
                        source = existing?.source ?: "manual",
                    )
                    viewModel.saveCourse(entity, existing == null)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && semester.isNotBlank(),
            ) { Text("保存") }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("删除课程") }
            }
        }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这门课？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCourse(existing)
                    confirmDelete = false
                    onDone()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
