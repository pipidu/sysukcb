package cn.sysu.kcb.ui.course

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.remote.createJwxtJson
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.motion.SeamlessSource
import cn.sysu.kcb.ui.motion.startWithOplusViewSeamless
import cn.sysu.kcb.ui.theme.KcbTheme
import cn.sysu.kcb.ui.theme.KcbTopBar
import kotlinx.serialization.builtins.ListSerializer

class CourseDetailActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)
        val themeColor = intent.getLongExtra(EXTRA_THEME_COLOR, 0xFF8C1A1AL)
        val ids = intent.getLongArrayExtra(EXTRA_IDS) ?: longArrayOf()
        var courses by mutableStateOf(decodeCourses(intent.getStringExtra(EXTRA_COURSES)))
        val periods = decodePeriods(intent.getStringExtra(EXTRA_PERIODS))
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            LaunchedEffect(ids.joinToString()) {
                if (readOnly || ids.isEmpty()) return@LaunchedEffect
                val latest = buildList {
                    for (id in ids) {
                        viewModel.getCourse(id)?.let { add(it) }
                    }
                }
                if (latest.isNotEmpty()) courses = latest
            }
            KcbTheme(themeColor = settings.themeColor.takeIf { it != 0L } ?: themeColor, themeMode = settings.themeMode) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        KcbTopBar {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                            }
                            Text(if (courses.size > 1) "课程详情" else courses.firstOrNull()?.courseName.orEmpty().ifBlank { "课程详情" })
                        }
                    },
                ) { inner ->
                    CourseDetailBody(
                        courses = courses,
                        periods = periods,
                        themeColor = settings.themeColor.takeIf { it != 0L } ?: themeColor,
                        onEdit = if (readOnly) {
                            null
                        } else {
                            { course ->
                                CourseEditActivity.start(this@CourseDetailActivity, course.id, course.dayOfWeek, course.startPeriod, course.acadYearSemester, null)
                            }
                        },
                        modifier = Modifier
                            .padding(inner)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_COURSES = "courses_json"
        private const val EXTRA_PERIODS = "periods_json"
        private const val EXTRA_IDS = "course_ids"
        private const val EXTRA_READ_ONLY = "read_only"
        private const val EXTRA_THEME_COLOR = "theme_color"
        private val json = createJwxtJson()

        fun start(
            context: Context,
            courses: List<CourseEntity>,
            periods: List<PeriodEntity>,
            themeColor: Long,
            readOnly: Boolean,
            source: SeamlessSource?,
        ) {
            val intent = Intent(context, CourseDetailActivity::class.java)
                .putExtra(EXTRA_COURSES, json.encodeToString(ListSerializer(CourseEntity.serializer()), courses))
                .putExtra(EXTRA_PERIODS, json.encodeToString(ListSerializer(PeriodEntity.serializer()), periods))
                .putExtra(EXTRA_IDS, courses.map { it.id }.toLongArray())
                .putExtra(EXTRA_READ_ONLY, readOnly)
                .putExtra(EXTRA_THEME_COLOR, themeColor)
            val activity = context as? Activity
            if (activity != null) activity.startWithOplusViewSeamless(intent, source)
            else context.startActivity(intent)
        }

        private fun decodeCourses(raw: String?): List<CourseEntity> =
            runCatching {
                json.decodeFromString(ListSerializer(CourseEntity.serializer()), raw.orEmpty())
            }.getOrDefault(emptyList())

        private fun decodePeriods(raw: String?): List<PeriodEntity> =
            runCatching {
                json.decodeFromString(ListSerializer(PeriodEntity.serializer()), raw.orEmpty())
            }.getOrDefault(emptyList())
    }
}
