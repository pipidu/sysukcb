package cn.sysu.kcb.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.MainActivity
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.notify.ClassAlarmScheduler
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TodayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

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
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.load(context)
        provideContent {
            GlanceTheme {
                WeekContent(state)
            }
        }
    }
}

class NextWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.load(context)
        provideContent {
            GlanceTheme {
                NextContent(state)
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

class NextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextWidget()
}

data class UpcomingItem(
    val name: String,
    val whenText: String,
    val place: String,
    val color: Long,
    val ongoing: Boolean,
)

data class WidgetState(
    val theme: Long,
    val title: String,
    val subtitle: String,
    val nextTitle: String,
    val today: List<CourseEntity>,
    val weekCourses: List<CourseEntity>,
    val periods: List<PeriodEntity>,
    val weekNo: Int,
    val upcoming: List<UpcomingItem>,
)

object WidgetData {
    suspend fun load(context: Context): WidgetState {
        val app = context.applicationContext as? KcbApp ?: KcbApp.instance
        val settings = app.container.settings.snapshot()
        val current = app.container.timetable.currentSemester()
        val semester = settings.selectedSemester.ifBlank { current?.acadYearSemester.orEmpty() }
        val semesterMeta = app.container.timetable.listSemesters().firstOrNull { it.acadYearSemester == semester }
        val startMillis = semesterMeta?.startMillis ?: 0L
        val weeks = app.container.timetable.listWeeks(semester)
        val courses = app.container.timetable.listCourses(semester)
        val periods = app.container.timetable.listPeriods(semester)
        val today = LocalDate.now()
        val weekNo = ClassAlarmScheduler.resolveWeek(today, weeks, startMillis)
        val todayCourses = if (weekNo == null) {
            emptyList()
        } else {
            courses
                .filter { it.dayOfWeek == today.dayOfWeek.value && WeekMask.has(it.weeksMask, weekNo) }
                .sortedBy { it.startPeriod }
        }
        val weekday = weekdayName(today.dayOfWeek.value)
        return WidgetState(
            theme = settings.themeColor,
            title = "今日课程",
            subtitle = if (weekNo != null) "第${weekNo}周 · $weekday" else "学期未开始",
            nextTitle = if (weekNo != null) {
                "${today.monthValue}/${today.dayOfMonth} $weekday · 第${weekNo}周"
            } else {
                "${today.monthValue}/${today.dayOfMonth} $weekday · 学期未开始"
            },
            today = todayCourses,
            weekCourses = if (weekNo == null) emptyList() else courses.filter { WeekMask.has(it.weeksMask, weekNo) },
            periods = periods,
            weekNo = weekNo ?: 0,
            upcoming = upcomingClasses(courses, periods, weeks, startMillis, 2, settings.themeColor),
        )
    }

    suspend fun refreshAll(context: Context) {
        TodayWidget().updateAll(context)
        WeekWidget().updateAll(context)
        NextWidget().updateAll(context)
    }

    private fun weekdayName(day: Int) = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(day - 1) { "" }

    private fun upcomingClasses(
        courses: List<CourseEntity>,
        periods: List<PeriodEntity>,
        weeks: List<WeekEntity>,
        semesterStart: Long,
        limit: Int,
        theme: Long,
    ): List<UpcomingItem> {
        val now = java.time.LocalDateTime.now()
        val today = now.toLocalDate()
        val periodMap = periods.associateBy { it.sectionNumber }
        val result = mutableListOf<UpcomingItem>()
        for (offset in 0..27) {
            if (result.size >= limit) break
            val date = today.plusDays(offset.toLong())
            val weekNo = ClassAlarmScheduler.resolveWeek(date, weeks, semesterStart) ?: continue
            val dayCourses = courses
                .filter { it.dayOfWeek == date.dayOfWeek.value && WeekMask.has(it.weeksMask, weekNo) }
                .sortedBy { it.startPeriod }
            for (course in dayCourses) {
                val startRaw = periodMap[course.startPeriod]?.startTime.orEmpty()
                val endRaw = periodMap[course.endPeriod]?.endTime.orEmpty()
                val start = parseHm(startRaw)
                val end = parseHm(endRaw)
                if (date == today) {
                    if (end != null && !now.toLocalTime().isBefore(end)) continue
                }
                val ongoing = date == today && start != null && end != null &&
                    !now.toLocalTime().isBefore(start) && now.toLocalTime().isBefore(end)
                val timeRange = listOf(startRaw, endRaw).filter { it.isNotBlank() }.joinToString("-")
                val whenText = when {
                    ongoing -> "正在上课 $timeRange".trim()
                    offset == 0 -> timeRange.ifBlank { "今天" }
                    offset == 1 -> "明天 $timeRange".trim()
                    else -> "${date.monthValue}/${date.dayOfMonth} ${weekdayName(date.dayOfWeek.value)} $timeRange".trim()
                }
                result += UpcomingItem(
                    name = course.courseName.trim(),
                    whenText = whenText,
                    place = compactPlace(course.place),
                    color = CourseColors.display(course.color, course.courseName, theme),
                    ongoing = ongoing,
                )
                if (result.size >= limit) break
            }
        }
        return result
    }

    private fun parseHm(raw: String): LocalTime? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return runCatching { LocalTime.parse(value) }.getOrNull()
            ?: runCatching { LocalTime.parse(value, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()
    }
}

private fun compactPlace(place: String): String =
    place.replace(Regex("（\\d+座）|\\(\\d+座\\)"), "")
        .replace('/', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

@Composable
private fun openAppAction(): androidx.glance.action.Action {
    val context = LocalContext.current
    return actionStartActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
}

@Composable
private fun TodayContent(state: WidgetState) {
    val theme = Color(state.theme)
    val open = openAppAction()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .background(ColorProvider(Color.White))
            .clickable(open)
            .padding(14.dp),
    ) {
        Text(state.title, style = TextStyle(color = ColorProvider(theme), fontWeight = FontWeight.Bold, fontSize = 16.sp))
        Text(state.subtitle, style = TextStyle(color = ColorProvider(Color(0xFF667085)), fontSize = 12.sp))
        Spacer(GlanceModifier.height(10.dp))
        if (state.today.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(12.dp).background(ColorProvider(Color(0xFFF7F4F4))),
                contentAlignment = Alignment.Center,
            ) {
                Text("今天没有课", style = TextStyle(color = ColorProvider(Color(0xFF98A2B3)), fontSize = 13.sp))
            }
        } else {
            state.today.take(4).forEach { course ->
                val start = state.periods.firstOrNull { it.sectionNumber == course.startPeriod }?.startTime.orEmpty()
                val end = state.periods.firstOrNull { it.sectionNumber == course.endPeriod }?.endTime.orEmpty()
                val cardColor = CourseColors.display(course.color, course.courseName, state.theme)
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .cornerRadius(12.dp)
                        .background(ColorProvider(Color(cardColor).copy(alpha = 0.55f)))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = GlanceModifier
                            .width(4.dp)
                            .height(36.dp)
                            .cornerRadius(4.dp)
                            .background(ColorProvider(Color(cardColor))),
                    ) {
                        Spacer(GlanceModifier.width(4.dp))
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            course.courseName,
                            style = TextStyle(color = ColorProvider(Color(0xFF1D2939)), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                        Text(
                            start.takeIf { it.isNotBlank() }?.let { "$it-${end}" } ?: compactPlace(course.place),
                            style = TextStyle(color = ColorProvider(Color(0xFF667085)), fontSize = 11.sp),
                            maxLines = 1,
                        )
                        if (start.isNotBlank() && course.place.isNotBlank()) {
                            Text(
                                compactPlace(course.place),
                                style = TextStyle(color = ColorProvider(Color(0xFF667085)), fontSize = 11.sp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekContent(state: WidgetState) {
    val theme = Color(state.theme)
    val days = listOf("一", "二", "三", "四", "五")
    val open = openAppAction()
    val periods = (state.periods.map { it.sectionNumber }.ifEmpty { (1..8).toList() }).take(8)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .background(ColorProvider(Color.White))
            .clickable(open)
            .padding(8.dp),
    ) {
        Text(
            if (state.weekNo > 0) "第${state.weekNo}周课表" else "学期未开始",
            style = TextStyle(color = ColorProvider(theme), fontWeight = FontWeight.Bold, fontSize = 14.sp),
        )
        Spacer(GlanceModifier.height(6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Spacer(GlanceModifier.width(16.dp))
            days.forEach { day ->
                Text(
                    day,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF667085)),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        periods.forEach { period ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$period",
                    style = TextStyle(color = ColorProvider(Color(0xFF98A2B3)), fontSize = 10.sp, textAlign = TextAlign.Center),
                    modifier = GlanceModifier.width(16.dp),
                )
                for (day in 1..5) {
                    val course = state.weekCourses.firstOrNull {
                        it.dayOfWeek == day && period >= it.startPeriod && period <= it.endPeriod
                    }
                    val shown = course?.let { CourseColors.display(it.color, it.courseName, state.theme) }
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .padding(1.dp)
                            .cornerRadius(4.dp)
                            .background(
                                ColorProvider(
                                    if (shown != null) Color(shown) else Color(0xFFF2F4F7),
                                ),
                            )
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (course != null && shown != null && period == course.startPeriod) {
                            Text(
                                course.courseName.take(6),
                                style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, textAlign = TextAlign.Center),
                                maxLines = 2,
                            )
                        } else {
                            Spacer(GlanceModifier.width(1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextContent(state: WidgetState) {
    val theme = Color(state.theme)
    val open = openAppAction()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .background(ColorProvider(Color.White))
            .clickable(open)
            .padding(6.dp),
    ) {
        Text(
            state.nextTitle,
            style = TextStyle(
                color = ColorProvider(theme),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(4.dp))
        if (state.upcoming.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(10.dp).background(ColorProvider(Color(0xFFF7F4F4))),
                contentAlignment = Alignment.Center,
            ) {
                Text("最近没有课", style = TextStyle(color = ColorProvider(Color(0xFF98A2B3)), fontSize = 11.sp))
            }
        } else {
            state.upcoming.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(GlanceModifier.height(6.dp))
                }
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                        .cornerRadius(10.dp)
                        .background(ColorProvider(Color(item.color)))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = GlanceModifier
                            .width(3.dp)
                            .height(32.dp)
                            .cornerRadius(3.dp)
                            .background(ColorProvider(Color.White.copy(alpha = 0.85f))),
                    ) {
                        Spacer(GlanceModifier.width(3.dp))
                    }
                    Spacer(GlanceModifier.width(6.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            item.name,
                            style = TextStyle(
                                color = ColorProvider(Color.White),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                        Text(
                            item.whenText,
                            style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.92f)), fontSize = 8.sp),
                            maxLines = 1,
                        )
                        if (item.place.isNotBlank()) {
                            Text(
                                item.place,
                                style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.92f)), fontSize = 8.sp),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}
