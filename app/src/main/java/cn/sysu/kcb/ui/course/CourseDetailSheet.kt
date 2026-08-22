package cn.sysu.kcb.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.domain.WeekMask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: CourseEntity,
    periods: List<PeriodEntity>,
    onDismiss: () -> Unit,
) {
    val start = periods.firstOrNull { it.sectionNumber == course.startPeriod }?.startTime.orEmpty()
    val end = periods.firstOrNull { it.sectionNumber == course.endPeriod }?.endTime.orEmpty()
    val day = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(course.dayOfWeek - 1) { "周${course.dayOfWeek}" }
    val timeRange = listOfNotNull(
        start.takeIf { it.isNotBlank() },
        end.takeIf { it.isNotBlank() },
    ).joinToString("-").ifBlank { "第${course.startPeriod}-${course.endPeriod}节" }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(course.courseName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            DetailLine("时间", "$day  第${course.startPeriod}-${course.endPeriod}节  $timeRange")
            if (course.teacher.isNotBlank()) DetailLine("教师", course.teacher)
            if (course.place.isNotBlank()) DetailLine("地点", course.place)
            val weeks = WeekMask.describe(course.weeksMask).ifBlank { course.timeDetail }
            if (weeks.isNotBlank()) DetailLine("周次", weeks)
            if (course.notes.isNotBlank()) DetailLine("备注", course.notes)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
