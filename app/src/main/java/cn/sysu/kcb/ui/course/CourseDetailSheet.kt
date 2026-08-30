package cn.sysu.kcb.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.domain.WeekMask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    courses: List<CourseEntity>,
    periods: List<PeriodEntity>,
    onDismiss: () -> Unit,
    onEdit: ((CourseEntity) -> Unit)? = null,
    bottomInset: Dp = 0.dp,
) {
    if (courses.isEmpty()) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = bottomInset.coerceAtLeast(48.dp) + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (courses.size > 1) {
                Text(
                    "该时段有 ${courses.size} 门课",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    style = compactText,
                )
            }
            courses.forEachIndexed { index, course ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                CourseDetailBlock(course, periods, onEdit)
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun CourseDetailBlock(
    course: CourseEntity,
    periods: List<PeriodEntity>,
    onEdit: ((CourseEntity) -> Unit)?,
) {
    val start = periods.firstOrNull { it.sectionNumber == course.startPeriod }?.startTime.orEmpty()
    val end = periods.firstOrNull { it.sectionNumber == course.endPeriod }?.endTime.orEmpty()
    val day = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(course.dayOfWeek - 1) { "周${course.dayOfWeek}" }
    val timeRange = listOfNotNull(
        start.takeIf { it.isNotBlank() },
        end.takeIf { it.isNotBlank() },
    ).joinToString("-").ifBlank { "第${course.startPeriod}-${course.endPeriod}节" }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .width(5.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(course.color)),
            )
            Text(
                course.courseName,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                style = compactText,
                modifier = Modifier.weight(1f),
            )
        }
        DetailLine("时间", "$day  第${course.startPeriod}-${course.endPeriod}节  $timeRange")
        if (course.teacher.isNotBlank()) DetailLine("教师", course.teacher)
        if (course.place.isNotBlank()) DetailLine("地点", course.place)
        if (course.notes.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            DetailLine("备注", course.notes)
        }
        val weeks = WeekMask.describe(course.weeksMask).ifBlank { course.timeDetail }
        if (weeks.isNotBlank()) DetailLine("周次", weeks)
        if (onEdit != null) {
            OutlinedButton(
                onClick = { onEdit(course) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("编辑这门课") }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            style = compactText,
        )
        Text(
            value,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            style = compactText,
        )
    }
}

private val compactText = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)
