package cn.sysu.kcb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.sysu.kcb.BuildConfig
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.prefs.UserSettings
import cn.sysu.kcb.data.remote.AppUpdate
import cn.sysu.kcb.data.remote.SessionCheckResult
import cn.sysu.kcb.data.remote.SessionExpiredException
import cn.sysu.kcb.data.remote.SessionStatus
import cn.sysu.kcb.data.remote.isNewerThan
import cn.sysu.kcb.data.school.School
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
        SharingStarted.Eagerly,
        UserSettings(),
    )

    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)
    val importProgress = MutableStateFlow("")
    val loggedIn = MutableStateFlow(container.cookies.hasAnySession())
    val sessionStatus = MutableStateFlow(
        if (container.cookies.hasAnySession()) SessionStatus.Valid else SessionStatus.LoggedOut,
    )
    val checkingSession = MutableStateFlow(false)
    val openTimetableAt = MutableStateFlow(0L)
    val updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val webdavBusy = MutableStateFlow(false)
    val webdavHasPassword = MutableStateFlow(container.webdavSecrets.hasPassword())
    private var lastUpdateCheckAt = 0L

    fun consumeMessage() {
        message.value = null
    }

    fun checkForUpdate(manual: Boolean = false) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        if (!manual && updateState.value is UpdateCheckState.Available) return@launch
        if (!manual && now - lastUpdateCheckAt < 10 * 60 * 1000L && updateState.value !is UpdateCheckState.Idle) {
            return@launch
        }
        updateState.value = UpdateCheckState.Checking
        runCatching { container.updates.fetchLatest() }
            .onSuccess { latest ->
                lastUpdateCheckAt = System.currentTimeMillis()
                updateState.value = if (latest != null && latest.isNewerThan(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
                    UpdateCheckState.Available(latest)
                } else {
                    UpdateCheckState.UpToDate
                }
            }
            .onFailure {
                updateState.value = if (manual) {
                    UpdateCheckState.Failed(it.message ?: "检查更新失败")
                } else {
                    UpdateCheckState.Idle
                }
            }
    }

    fun checkSession() = viewModelScope.launch {
        checkingSession.value = true
        val result = runCatching { container.importer.checkSession() }.getOrElse {
            SessionCheckResult(SessionStatus.Unreachable, it.message.orEmpty())
        }
        sessionStatus.value = result.status
        loggedIn.value = result.status == SessionStatus.Valid
        checkingSession.value = false
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

    fun setSchool(schoolId: String) = viewModelScope.launch {
        if (schoolId.isBlank()) return@launch
        val current = container.settings.snapshot().schoolId
        if (current == schoolId) return@launch
        container.settings.setSchoolId(schoolId)
        container.cookies.clear()
        loggedIn.value = false
        sessionStatus.value = SessionStatus.LoggedOut
        message.value = "已切换到${School.of(schoolId).displayName}，请重新登录"
    }

    fun setSemester(semester: String) = viewModelScope.launch {
        if (semester.isBlank()) return@launch
        val current = container.settings.snapshot().selectedSemester
        if (current == semester) return@launch
        container.settings.setSelectedSemester(semester)
        refreshAlarms()
        WidgetData.refreshAll(getApplication())
    }

    fun importFromJwxt(semester: String? = null) = importInternal(
        onlyCurrent = true,
        semester = semester ?: settings.value.selectedSemester.ifBlank { null },
    )

    fun importAllYears() = importInternal(onlyCurrent = false)

    private fun importInternal(onlyCurrent: Boolean, semester: String? = null) = viewModelScope.launch {
        importing.value = true
        importProgress.value = "正在连接教务系统…"
        runCatching {
            container.importer.importAllYears(
                onlyCurrent = onlyCurrent,
                semesterOverride = semester,
            ) { importProgress.value = it }
        }
            .onSuccess {
                loggedIn.value = true
                sessionStatus.value = SessionStatus.Valid
                message.value = if (onlyCurrent) "已导入 $it 的课表和考试" else "已导入前后各 8 学期课表，教务当前学期 $it"
                openTimetableAt.value = System.currentTimeMillis()
                refreshAlarms()
                WidgetData.refreshAll(getApplication())
            }
            .onFailure {
                loggedIn.value = container.cookies.hasAnySession()
                if (it is SessionExpiredException) sessionStatus.value = SessionStatus.Expired
                message.value = when (it) {
                    is SessionExpiredException -> it.message
                    else -> it.message ?: "导入失败"
                }
            }
        importing.value = false
        importProgress.value = ""
    }

    fun importJson(text: String) = viewModelScope.launch {
        importing.value = true
        runCatching { container.share.importJson(text) }
            .onSuccess {
                if (it.isNotBlank()) container.settings.setSelectedSemester(it)
                message.value = "已从文件导入课表"
                openTimetableAt.value = System.currentTimeMillis()
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

    fun saveWebDav(url: String, user: String, password: String) = viewModelScope.launch {
        persistWebDav(url, user, password)
        message.value = "已保存 WebDAV 设置"
    }

    fun uploadWebDav(url: String, user: String, password: String) = viewModelScope.launch {
        persistWebDav(url, user, password)
        webdavBusy.value = true
        runCatching { container.webdav.upload() }
            .onSuccess { message.value = "已上传课表到 WebDAV" }
            .onFailure { message.value = it.message ?: "上传失败" }
        webdavBusy.value = false
    }

    fun downloadWebDav(url: String, user: String, password: String) = viewModelScope.launch {
        persistWebDav(url, user, password)
        webdavBusy.value = true
        importing.value = true
        runCatching { container.webdav.download() }
            .onSuccess {
                if (it.isNotBlank()) container.settings.setSelectedSemester(it)
                message.value = "已从 WebDAV 导入课表"
                openTimetableAt.value = System.currentTimeMillis()
                refreshAlarms()
                WidgetData.refreshAll(getApplication())
            }
            .onFailure { message.value = it.message ?: "下载失败" }
        importing.value = false
        webdavBusy.value = false
    }

    private suspend fun persistWebDav(url: String, user: String, password: String) {
        container.settings.setWebDav(url, user)
        if (password.isNotBlank()) container.webdavSecrets.save(password)
        webdavHasPassword.value = container.webdavSecrets.hasPassword()
    }

    fun prepareFreshLogin() {
        container.cookies.clear()
        loggedIn.value = false
        sessionStatus.value = SessionStatus.LoggedOut
    }

    fun logout() = viewModelScope.launch {
        container.cookies.clear()
        loggedIn.value = false
        sessionStatus.value = SessionStatus.LoggedOut
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

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data object UpToDate : UpdateCheckState()
    data class Available(val update: AppUpdate) : UpdateCheckState()
    data class Failed(val message: String) : UpdateCheckState()
}
