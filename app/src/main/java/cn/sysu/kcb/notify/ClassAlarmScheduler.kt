package cn.sysu.kcb.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.MainActivity
import cn.sysu.kcb.R
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.prefs.UserSettings
import cn.sysu.kcb.domain.WeekMask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ClassAlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CLASS, context.getString(R.string.channel_class), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EXAM, context.getString(R.string.channel_exam), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    suspend fun reschedule(
        courses: List<CourseEntity>,
        exams: List<ExamEntity>,
        periods: List<PeriodEntity>,
        weeks: List<WeekEntity>,
        settings: UserSettings,
    ) {
        ensureChannels()
        cancelUpcoming()
        val now = LocalDateTime.now()
        if (settings.reminderEnabled) {
            val periodMap = periods.associateBy { it.sectionNumber }
            val today = LocalDate.now()
            for (offset in 0..13) {
                val date = today.plusDays(offset.toLong())
                val weekNo = resolveWeek(date, weeks) ?: continue
                for (course in courses) {
                    if (course.dayOfWeek != date.dayOfWeek.value) continue
                    if (!WeekMask.has(course.weeksMask, weekNo)) continue
                    val start = periodMap[course.startPeriod]?.startTime ?: continue
                    val startTime = runCatching { LocalTime.parse(start) }.getOrNull() ?: continue
                    val trigger = LocalDateTime.of(date, startTime).minusMinutes(settings.reminderMinutes.toLong())
                    if (trigger.isAfter(now)) {
                        schedule(
                            requestCode = requestCode("c", course.id, date.toString()),
                            at = trigger,
                            title = "即将上课",
                            body = "${course.courseName} ${start} ${course.place}".trim(),
                            channel = CHANNEL_CLASS,
                        )
                    }
                }
            }
        }
        if (settings.examReminderEnabled) {
            for (exam in exams) {
                val date = runCatching { LocalDate.parse(exam.examDate) }.getOrNull() ?: continue
                val start = runCatching { LocalTime.parse(exam.startTime.ifBlank { "08:00" }) }.getOrNull() ?: continue
                val trigger = LocalDateTime.of(date, start).minusMinutes(settings.examReminderMinutes.toLong())
                if (trigger.isAfter(now)) {
                    schedule(
                        requestCode = requestCode("e", exam.id, exam.examDate),
                        at = trigger,
                        title = "考试提醒",
                        body = "${exam.subjectName} ${exam.startTime} ${exam.classroom}".trim(),
                        channel = CHANNEL_EXAM,
                    )
                }
            }
        }
    }

    private fun schedule(requestCode: Int, at: LocalDateTime, title: String, body: String, channel: String) {
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) return
        val intent = Intent(context, ClassAlarmReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_CHANNEL, channel)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }

    private fun cancelUpcoming() {
        for (i in 0 until 400) {
            val pending = PendingIntent.getBroadcast(
                context,
                i,
                Intent(context, ClassAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pending != null) {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    private fun requestCode(prefix: String, id: Long, extra: String): Int {
        return (prefix + id + extra).hashCode() and 0x7fffffff % 400
    }

    companion object {
        const val CHANNEL_CLASS = "class_reminders"
        const val CHANNEL_EXAM = "exam_reminders"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_CHANNEL = "channel"

        fun resolveWeek(date: LocalDate, weeks: List<WeekEntity>, semesterStartMillis: Long = 0): Int? {
            for (week in weeks) {
                val start = week.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
                val end = week.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
                if (!date.isBefore(start) && !date.isAfter(end)) return week.weekly
            }
            val max = weeks.maxOfOrNull { it.weekly } ?: WeekMask.MAX_WEEK
            val dated = weeks.filter { !it.startDate.isNullOrBlank() }
            val known = dated.minByOrNull { it.weekly }
            if (known != null) {
                val start = runCatching { LocalDate.parse(known.startDate) }.getOrNull()
                if (start != null) {
                    val origin = mondayOf(start)
                    if (date.isBefore(origin)) return null
                    val week = known.weekly + (ChronoUnit.DAYS.between(origin, date) / 7).toInt()
                    if (week in 1..max) return week
                    return null
                }
            }
            if (semesterStartMillis > 0) {
                val start = java.time.Instant.ofEpochMilli(semesterStartMillis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val origin = mondayOf(start)
                if (date.isBefore(origin)) return null
                val week = (ChronoUnit.DAYS.between(origin, date) / 7).toInt() + 1
                if (week in 1..max) return week
            }
            return null
        }

        private fun mondayOf(date: LocalDate): LocalDate =
            date.minusDays((date.dayOfWeek.value - 1).toLong())
    }
}

class ClassAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ClassAlarmScheduler.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(ClassAlarmScheduler.EXTRA_BODY).orEmpty()
        val channel = intent.getStringExtra(ClassAlarmScheduler.EXTRA_CHANNEL)
            ?: ClassAlarmScheduler.CHANNEL_CLASS
        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(launch)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as KcbApp
                val container = app.container
                val settings = container.settings.snapshot()
                val semester = settings.selectedSemester.ifBlank {
                    container.timetable.currentSemester()?.acadYearSemester.orEmpty()
                }
                if (semester.isNotBlank()) {
                    container.alarms.reschedule(
                        courses = container.timetable.listCourses(semester),
                        exams = container.timetable.listAllExams(),
                        periods = container.timetable.listPeriods(semester),
                        weeks = container.timetable.listWeeks(semester),
                        settings = settings,
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
