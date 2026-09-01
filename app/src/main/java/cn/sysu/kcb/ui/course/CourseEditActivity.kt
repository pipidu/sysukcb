package cn.sysu.kcb.ui.course

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.motion.SeamlessSource
import cn.sysu.kcb.ui.motion.startWithOplusViewSeamless
import cn.sysu.kcb.ui.theme.KcbTheme

class CourseEditActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L).takeIf { it > 0L }
        val day = intent.getIntExtra(EXTRA_DAY, 1)
        val period = intent.getIntExtra(EXTRA_PERIOD, 1)
        val semester = intent.getStringExtra(EXTRA_SEMESTER).orEmpty()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            KcbTheme(themeColor = settings.themeColor, themeMode = settings.themeMode) {
                CourseEditScreen(
                    viewModel = viewModel,
                    courseId = courseId,
                    presetDay = day,
                    presetPeriod = period,
                    semester = semester.ifBlank { settings.selectedSemester },
                    onDone = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_COURSE_ID = "course_id"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_PERIOD = "period"
        private const val EXTRA_SEMESTER = "semester"

        fun start(
            context: Context,
            courseId: Long?,
            day: Int,
            period: Int,
            semester: String,
            source: SeamlessSource?,
        ) {
            val intent = Intent(context, CourseEditActivity::class.java)
                .putExtra(EXTRA_COURSE_ID, courseId ?: 0L)
                .putExtra(EXTRA_DAY, day)
                .putExtra(EXTRA_PERIOD, period)
                .putExtra(EXTRA_SEMESTER, semester)
            val activity = context as? Activity
            if (activity != null) activity.startWithOplusViewSeamless(intent, source)
            else context.startActivity(intent)
        }
    }
}
