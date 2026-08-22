package cn.sysu.kcb.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.notify.ClassAlarmScheduler
import java.time.LocalDate

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.load(context)
        provideContent {
            GlanceTheme {
                TodayContent(state)
            }
        }
    }
}

class WeekWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.load(context)
        provideContent {
            GlanceTheme {
                WeekContent(state)
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}

data class WidgetState(
    val theme: Long,
    val title: String,
    val subtitle: String,
    val today: List<CourseEntity>,
    val weekCourses: List<CourseEntity>,
    val periods: List<PeriodEntity>,
    val weekNo: Int,
)

object WidgetData {
    suspend fun load(context: Context): WidgetState {
        val app = context.applicationContext as KcbApp
        val settings = app.container.settings.snapshot()
        val semester = settings.selectedSemester.ifBlank {
            app.container.timetable.currentSemester()?.acadYearSemester.orEmpty()
        }
        val weeks = app.container.timetable.listWeeks(semester)
        val courses = app.container.timetable.listCourses(semester)
        val periods = app.container.timetable.listPeriods(semester)
        val today = LocalDate.now()
        val weekNo = ClassAlarmScheduler.resolveWeek(today, weeks) ?: 1
        val todayCourses = courses
            .filter { it.dayOfWeek == today.dayOfWeek.value && WeekMask.has(it.weeksMask, weekNo) }
            .sortedBy { it.startPeriod }
        return WidgetState(
            theme = settings.themeColor,
            title = "今日课程",
            subtitle = "第${weekNo}周 · ${weekdayName(today.dayOfWeek.value)}",
            today = todayCourses,
            weekCourses = courses.filter { WeekMask.has(it.weeksMask, weekNo) },
            periods = periods,
            weekNo = weekNo,
        )
    }

    suspend fun refreshAll(context: Context) {
        TodayWidget().updateAll(context)
        WeekWidget().updateAll(context)
    }

    private fun weekdayName(day: Int) = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(day - 1) { "" }
}

@Composable
private fun TodayContent(state: WidgetState) {
    val bg = Color(state.theme)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFFF7F4F4))).padding(12.dp),
    ) {
        Text(state.title, style = TextStyle(color = ColorProvider(bg), fontWeight = FontWeight.Bold, fontSize = 16.sp))
        Text(state.subtitle, style = TextStyle(color = ColorProvider(Color(0xFF6D6D6D)), fontSize = 12.sp))
        Spacer(GlanceModifier.height(8.dp))
        if (state.today.isEmpty()) {
            Text("今天没有课", style = TextStyle(color = ColorProvider(Color(0xFF8A8A8A)), fontSize = 13.sp))
        } else {
            state.today.take(4).forEach { course ->
                val period = state.periods.firstOrNull { it.sectionNumber == course.startPeriod }
                Text(
                    "${period?.startTime ?: ""}  ${course.courseName}",
                    style = TextStyle(color = ColorProvider(Color(0xFF222222)), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                if (course.place.isNotBlank()) {
                    Text(course.place, style = TextStyle(color = ColorProvider(Color(0xFF6D6D6D)), fontSize = 11.sp))
                }
                Spacer(GlanceModifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun WeekContent(state: WidgetState) {
    val bg = Color(state.theme)
    val days = listOf("一", "二", "三", "四", "五")
    Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFFF7F4F4))).padding(8.dp),
    ) {
        Text("第${state.weekNo}周", style = TextStyle(color = ColorProvider(bg), fontWeight = FontWeight.Bold, fontSize = 14.sp))
        Spacer(GlanceModifier.height(6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Spacer(GlanceModifier.width(18.dp))
            days.forEach { day ->
                Text(
                    day,
                    style = TextStyle(color = ColorProvider(Color(0xFF6D6D6D)), fontSize = 11.sp),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        for (period in 1..8) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$period", style = TextStyle(color = ColorProvider(Color(0xFF9A9A9A)), fontSize = 10.sp), modifier = GlanceModifier.width(18.dp))
                for (day in 1..5) {
                    val course = state.weekCourses.firstOrNull {
                        it.dayOfWeek == day && period >= it.startPeriod && period <= it.endPeriod
                    }
                    Text(
                        course?.courseName?.take(4).orEmpty(),
                        style = TextStyle(color = ColorProvider(if (course != null) bg else Color.Transparent), fontSize = 9.sp),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }
        }
    }
}
