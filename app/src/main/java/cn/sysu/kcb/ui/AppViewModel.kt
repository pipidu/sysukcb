package cn.sysu.kcb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.prefs.UserSettings
import cn.sysu.kcb.data.remote.SessionExpiredException
import cn.sysu.kcb.widget.WidgetData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as KcbApp).container

    val settings: StateFlow<UserSettings> = container.settings.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserSettings(),
    )

    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)
    val loggedIn = MutableStateFlow(container.cookies.hasSession())

    fun consumeMessage() {
        message.value = null
    }

    fun setThemeColor(color: Long) = viewModelScope.launch {
        container.settings.setThemeColor(color)
        WidgetData.refreshAll(getApplication())
    }

    fun setReminderEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settings.setReminderEnabled(enabled)
        refreshAlarms()
    }

    fun setReminderMinutes(minutes: Int) = viewModelScope.launch {
        container.settings.setReminderMinutes(minutes)
        refreshAlarms()
    }

    fun setExamReminderEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settings.setExamReminderEnabled(enabled)
        refreshAlarms()
    }

    fun setExamReminderMinutes(minutes: Int) = viewModelScope.launch {
        container.settings.setExamReminderMinutes(minutes)
        refreshAlarms()
    }

    fun setSemester(semester: String) = viewModelScope.launch {
        container.settings.setSelectedSemester(semester)
        refreshAlarms()
        WidgetData.refreshAll(getApplication())
    }

    fun importFromJwxt(semester: String? = null) = viewModelScope.launch {
        importing.value = true
        runCatching { container.importer.importAll(semester) }
            .onSuccess {
                loggedIn.value = true
                message.value = "已导入 $it 的课表和考试"
                refreshAlarms()
                WidgetData.refreshAll(getApplication())
            }
            .onFailure {
                loggedIn.value = container.cookies.hasSession()
                message.value = when (it) {
                    is SessionExpiredException -> it.message
                    else -> it.message ?: "导入失败"
                }
            }
        importing.value = false
    }

    fun importJson(text: String) = viewModelScope.launch {
        importing.value = true
        runCatching { container.share.importJson(text) }
            .onSuccess {
                if (it.isNotBlank()) container.settings.setSelectedSemester(it)
                message.value = "已从文件导入课表"
                refreshAlarms()
                WidgetData.refreshAll(getApplication())
            }
            .onFailure { message.value = it.message ?: "文件导入失败" }
        importing.value = false
    }

    fun exportSemester(semester: String) = viewModelScope.launch {
        runCatching {
            val file = container.share.exportSemester(semester)
            container.share.shareFile(file)
        }.onFailure { message.value = it.message ?: "导出失败" }
    }

    fun logout() = viewModelScope.launch {
        container.cookies.clear()
        loggedIn.value = false
        message.value = "已退出登录（本地课表仍保留）"
    }

    fun clearLocal() = viewModelScope.launch {
        container.timetable.clearAll()
        refreshAlarms()
        WidgetData.refreshAll(getApplication())
        message.value = "本地数据已清空"
    }

    fun saveCourse(course: CourseEntity, isNew: Boolean) = viewModelScope.launch {
        if (isNew) container.timetable.addCourse(course) else container.timetable.updateCourse(course)
        refreshAlarms()
        WidgetData.refreshAll(getApplication())
    }

    fun deleteCourse(course: CourseEntity) = viewModelScope.launch {
        container.timetable.deleteCourse(course)
        refreshAlarms()
        WidgetData.refreshAll(getApplication())
    }

    suspend fun getCourse(id: Long) = container.timetable.getCourse(id)

    private suspend fun refreshAlarms() {
        val snap = container.settings.snapshot()
        val semester = snap.selectedSemester.ifBlank {
            container.timetable.currentSemester()?.acadYearSemester.orEmpty()
        }
        container.alarms.reschedule(
            courses = if (semester.isBlank()) emptyList() else container.timetable.listCourses(semester),
            exams = container.timetable.listAllExams(),
            periods = if (semester.isBlank()) emptyList() else container.timetable.listPeriods(semester),
            weeks = if (semester.isBlank()) emptyList() else container.timetable.listWeeks(semester),
            settings = snap,
        )
    }
}
