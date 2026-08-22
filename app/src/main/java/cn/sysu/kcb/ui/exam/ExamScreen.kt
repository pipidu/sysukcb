package cn.sysu.kcb.ui.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(viewModel: AppViewModel) {
    val exams by KcbApp.instance.container.timetable.allExams()
        .collectAsStateWithLifecycle(emptyList())
    val grouped = exams.groupBy { "${it.acadYearSemester} · ${it.examWeekName.ifBlank { "其他" }}" }
    Scaffold(
        topBar = { TopAppBar(title = { Text("考试", fontWeight = FontWeight.SemiBold) }) },
    ) { inner ->
        if (exams.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("暂无考试信息", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                grouped.forEach { (weekName, list) ->
                    item(weekName) {
                        Text(weekName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp, top = 8.dp))
                    }
                    items(list, key = { it.id }) { exam ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(exam.subjectName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${exam.examDate}  ${exam.startTime}-${exam.endTime}")
                                if (exam.classroom.isNotBlank()) Text(exam.classroom)
                                val tags = listOf(exam.examMode, exam.examStage, exam.acadYearSemester).filter { it.isNotBlank() }
                                if (tags.isNotEmpty()) {
                                    Text(tags.joinToString(" · "), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
