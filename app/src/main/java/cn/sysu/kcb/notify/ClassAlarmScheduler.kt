package cn.sysu.kcb.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.MainActivity
import cn.sysu.kcb.R
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.prefs.SettingsRepository
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
    private val prefs = context.applicationContext.getSharedPreferences("kcb_alarms", Context.MODE_PRIVATE)

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val notifyAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun makeChannel(id: String, name: String, alarm: Boolean): NotificationChannel {
            val sound = if (alarm) alarmSound else notifySound
            val audio = if (alarm) alarmAudio else notifyAudio
            return NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = name
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 280, 180, 280)
                enableLights(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setSound(sound, audio)
                setShowBadge(true)
            }
        }
        manager.createNotificationChannel(makeChannel(CHANNEL_CLASS, context.getString(R.string.channel_class), alarm = false))
        manager.createNotificationChannel(makeChannel(CHANNEL_EXAM, context.getString(R.string.channel_exam), alarm = false))
        manager.createNotificationChannel(makeChannel(CHANNEL_CLASS_ALARM, context.getString(R.string.channel_class_alarm), alarm = true))
        manager.createNotificationChannel(makeChannel(CHANNEL_EXAM_ALARM, context.getString(R.string.channel_exam_alarm), alarm = true))
    }

    fun diagnostics(): ReminderDiagnostics {
        val notify = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val exact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        val battery = if (Build.VERSION.SDK_INT < 23) {
            true
        } else {
            val pm = context.getSystemService(PowerManager::class.java)
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return ReminderDiagnostics(notify, exact, battery)
    }

    fun showReminder(title: String, body: String, channel: String, alarmStyle: Boolean = false) {
        ensureChannels()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(launch)
            .setPriority(if (alarmStyle) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (alarmStyle) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val id = ((System.currentTimeMillis() % 1_000_000L).toInt() + 1000) and 0x7fffffff
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    fun scheduleTest(delaySeconds: Int = 10, alarmStyle: Boolean = false): String {
        ensureChannels()
        val diag = diagnostics()
        if (!diag.notificationsEnabled) {
            return "还不能弹出提醒：请先允许通知权限"
        }
        val channel = if (alarmStyle) CHANNEL_CLASS_ALARM else CHANNEL_CLASS
        val kind = if (alarmStyle) "闹钟" else "推送"
        showReminder("测试通知", "如果看到这一条，${kind}通知权限正常。", channel, alarmStyle)
        val trigger = LocalDateTime.now().plusSeconds(delaySeconds.toLong())
        val ok = schedule(
            requestCode = TEST_REQUEST_CODE,
            at = trigger,
            title = "测试提醒",
            body = "如果看到这一条，定时${kind}提醒正常。",
            channel = channel,
            persist = false,
            alarmStyle = alarmStyle,
        )
        return when {
            !ok -> "立刻测试通知已发出，但定时提醒预约失败"
            !diag.exactAlarmsAllowed ->
                "立刻测试通知已发出。准时闹钟未允许，${delaySeconds} 秒后的定时提醒可能不准或不会响"
            alarmStyle -> "立刻测试闹钟通知已发出，约 ${delaySeconds} 秒后还有一次（状态栏可能出现闹钟图标）"
            else -> "立刻测试推送通知已发出，约 ${delaySeconds} 秒后还有一次定时提醒"
        }
    }

    suspend fun reschedule(
        courses: List<CourseEntity>,
        exams: List<ExamEntity>,
        periods: List<PeriodEntity>,
        weeks: List<WeekEntity>,
        settings: UserSettings,
        semesterStartMillis: Long = 0L,
    ) {
        ensureChannels()
        cancelUpcoming()
        val now = LocalDateTime.now()
        val codes = linkedSetOf<String>()
        val alarmStyle = settings.reminderStyle == SettingsRepository.REMINDER_STYLE_ALARM
        val classChannel = if (alarmStyle) CHANNEL_CLASS_ALARM else CHANNEL_CLASS
        val examChannel = if (alarmStyle) CHANNEL_EXAM_ALARM else CHANNEL_EXAM
        if (settings.reminderEnabled) {
            val periodMap = periods.associateBy { it.sectionNumber }
            val today = LocalDate.now()
            for (offset in 0..13) {
                val date = today.plusDays(offset.toLong())
                val weekNo = resolveWeek(date, weeks, semesterStartMillis)
                for (course in courses) {
                    if (course.dayOfWeek != date.dayOfWeek.value) continue
                    if (weekNo != null && !WeekMask.has(course.weeksMask, weekNo)) continue
                    val start = periodMap[course.startPeriod]?.startTime ?: continue
                    val startTime = parseTime(start) ?: continue
                    val trigger = LocalDateTime.of(date, startTime)
                        .minusMinutes(settings.reminderMinutes.toLong())
                    if (!trigger.isAfter(now)) continue
                    val code = requestCode("c", course.id, date.toString())
                    if (schedule(code, trigger, "即将上课", "${course.courseName} $start ${course.place}".trim(), classChannel, persist = false, alarmStyle = alarmStyle)) {
                        codes += code.toString()
                    }
                }
            }
        }
        if (settings.examReminderEnabled) {
            for (exam in exams) {
                val date = parseDate(exam.examDate) ?: continue
                val start = parseTime(exam.startTime.ifBlank { "08:00" }) ?: continue
                val trigger = LocalDateTime.of(date, start)
                    .minusMinutes(settings.examReminderMinutes.toLong())
                if (!trigger.isAfter(now)) continue
                val code = requestCode("e", exam.id, exam.examDate)
                val body = "${exam.subjectName} ${exam.startTime} ${exam.classroom}".trim()
                if (schedule(code, trigger, "考试提醒", body, examChannel, persist = false, alarmStyle = alarmStyle)) {
                    codes += code.toString()
                }
            }
        }
        prefs.edit().putStringSet(KEY_CODES, codes).apply()
    }

    private fun schedule(
        requestCode: Int,
        at: LocalDateTime,
        title: String,
        body: String,
        channel: String,
        persist: Boolean = true,
        alarmStyle: Boolean = false,
    ): Boolean {
        val intent = Intent(context, ClassAlarmReceiver::class.java).apply {
            action = "$ACTION_REMIND.$requestCode"
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_CHANNEL, channel)
            putExtra(EXTRA_ALARM, alarmStyle)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (millis <= System.currentTimeMillis()) return false
        val show = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        val ok = runCatching {
            when {
                alarmStyle && exact ->
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), pending)
                exact ->
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
                else ->
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            }
            true
        }.getOrElse {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
                true
            }.getOrDefault(false)
        }
        if (ok && persist) {
            val codes = prefs.getStringSet(KEY_CODES, emptySet()).orEmpty().toMutableSet()
            codes += requestCode.toString()
            prefs.edit().putStringSet(KEY_CODES, codes).apply()
        }
        return ok
    }

    private fun cancelUpcoming() {
        val stored = prefs.getStringSet(KEY_CODES, emptySet()).orEmpty()
        val legacy = (0 until 400).map { it.toString() }
        for (raw in stored + legacy) {
            val code = raw.toIntOrNull() ?: continue
            cancelCode(code)
        }
        prefs.edit().remove(KEY_CODES).apply()
    }

    private fun cancelCode(requestCode: Int) {
        val intent = Intent(context, ClassAlarmReceiver::class.java).apply {
            action = "$ACTION_REMIND.$requestCode"
        }
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, flags)
            ?: PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, ClassAlarmReceiver::class.java),
                flags,
            )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    private fun requestCode(prefix: String, id: Long, extra: String): Int {
        var hash = (prefix + id + extra).hashCode()
        if (hash == Int.MIN_VALUE) hash = 0
        return hash and 0x7fffffff
    }

    companion object {
        const val CHANNEL_CLASS = "class_reminders_v2"
        const val CHANNEL_EXAM = "exam_reminders_v2"
        const val CHANNEL_CLASS_ALARM = "class_alarms_v2"
        const val CHANNEL_EXAM_ALARM = "exam_alarms_v2"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_ALARM = "alarm"
        const val ACTION_REMIND = "cn.sysu.kcb.action.REMIND"
        private const val KEY_CODES = "codes"
        private const val TEST_REQUEST_CODE = 0x6B636254

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

        fun parseTime(raw: String): LocalTime? {
            val text = raw.trim().replace("：", ":")
            runCatching { return LocalTime.parse(text) }
            val parts = text.split(":")
            val hour = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: return null
            val minute = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val second = parts.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            return runCatching { LocalTime.of(hour, minute, second) }.getOrNull()
        }

        fun parseDate(raw: String): LocalDate? {
            val text = raw.trim().take(10)
            return runCatching { LocalDate.parse(text) }.getOrNull()
        }

        private fun mondayOf(date: LocalDate): LocalDate =
            date.minusDays((date.dayOfWeek.value - 1).toLong())
    }
}

data class ReminderDiagnostics(
    val notificationsEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
    val ignoringBatteryOpt: Boolean,
)

class ClassAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ClassAlarmScheduler.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(ClassAlarmScheduler.EXTRA_BODY).orEmpty()
        val channel = intent.getStringExtra(ClassAlarmScheduler.EXTRA_CHANNEL)
            ?: ClassAlarmScheduler.CHANNEL_CLASS
        val alarmStyle = intent.getBooleanExtra(ClassAlarmScheduler.EXTRA_ALARM, false)
        val scheduler = (context.applicationContext as? KcbApp)?.container?.alarms
            ?: ClassAlarmScheduler(context)
        scheduler.showReminder(title, body, channel, alarmStyle)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rescheduleFromStore(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

suspend fun rescheduleFromStore(context: Context) {
    val app = context.applicationContext as? KcbApp ?: return
    val container = app.container
    val settings = container.settings.snapshot()
    val semester = settings.selectedSemester.ifBlank {
        container.timetable.currentSemester()?.acadYearSemester.orEmpty()
    }
    val semesters = container.timetable.listSemesters()
    val startMillis = semesters.find { it.acadYearSemester == semester }?.startMillis ?: 0L
    container.alarms.reschedule(
        courses = if (semester.isBlank()) emptyList() else container.timetable.listCourses(semester),
        exams = container.timetable.listAllExams(),
        periods = if (semester.isBlank()) emptyList() else container.timetable.listPeriods(semester),
        weeks = if (semester.isBlank()) emptyList() else container.timetable.listWeeks(semester),
        settings = settings,
        semesterStartMillis = startMillis,
    )
}
