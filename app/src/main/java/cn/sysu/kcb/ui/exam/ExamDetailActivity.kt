package cn.sysu.kcb.ui.exam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.remote.createJwxtJson
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.motion.SeamlessSource
import cn.sysu.kcb.ui.motion.startWithOplusViewSeamless
import cn.sysu.kcb.ui.theme.KcbTheme
import cn.sysu.kcb.ui.theme.KcbTopBar

class ExamDetailActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val exam = decode(intent.getStringExtra(EXTRA_EXAM)) ?: run {
            finish()
            return
        }
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            KcbTheme(themeColor = settings.themeColor, themeMode = settings.themeMode) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        KcbTopBar {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                            }
                            Text("考试详情")
                        }
                    },
                ) { inner ->
                    Column(
                        Modifier
                            .padding(inner)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(exam.subjectName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Detail("日期", exam.examDate)
                        Detail("时间", "${exam.startTime}-${exam.endTime}")
                        if (exam.duration.isNotBlank()) Detail("时长", exam.duration)
                        if (exam.classroom.isNotBlank()) Detail("地点", exam.classroom)
                        if (exam.examWeekName.isNotBlank()) Detail("考试周", exam.examWeekName)
                        if (exam.examMode.isNotBlank()) Detail("形式", exam.examMode)
                        if (exam.examStage.isNotBlank()) Detail("阶段", exam.examStage)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_EXAM = "exam_json"
        private val json = createJwxtJson()

        fun start(context: Context, exam: ExamEntity, source: SeamlessSource?) {
            val intent = Intent(context, ExamDetailActivity::class.java)
                .putExtra(EXTRA_EXAM, json.encodeToString(ExamEntity.serializer(), exam))
            val activity = context as? Activity
            if (activity != null) activity.startWithOplusViewSeamless(intent, source)
            else context.startActivity(intent)
        }

        private fun decode(raw: String?): ExamEntity? =
            runCatching { json.decodeFromString(ExamEntity.serializer(), raw.orEmpty()) }.getOrNull()
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
