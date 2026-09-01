package cn.sysu.kcb.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
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
import cn.sysu.kcb.data.remote.WebDavClient
import cn.sysu.kcb.data.remote.WebDavSyncWorker
import cn.sysu.kcb.data.school.School
import cn.sysu.kcb.widget.WidgetData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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
    val apkDownload = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle)
    val webdavBusy = MutableStateFlow(false)
    val webdavHasPassword = MutableStateFlow(container.webdavSecrets.hasPassword())
    private var lastUpdateCheckAt = 0L
    private var downloadJob: Job? = null

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

    fun downloadAndInstall(update: AppUpdate) {
        val url = update.apkUrl
        if (url.isNullOrBlank()) {
            apkDownload.value = ApkDownloadState.Failed("这个版本没有安装包")
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            apkDownload.value = ApkDownloadState.Progress(0L, 0L)
            val dest = File(getApplication<Application>().cacheDir, "updates/kcb-${update.versionName}.apk")
            runCatching {
                container.updates.downloadApk(url, dest) { received, total ->
                    apkDownload.value = ApkDownloadState.Progress(received, total)
                }
            }.onSuccess {
                apkDownload.value = ApkDownloadState.Installing
                runCatching { installDownloadedApk(dest) }
                    .onFailure { apkDownload.value = ApkDownloadState.Failed(it.message ?: "无法打开安装程序") }
            }.onFailure { error ->
                if (error is CancellationException) {
                    apkDownload.value = ApkDownloadState.Idle
                    throw error
                }
                apkDownload.value = ApkDownloadState.Failed(error.message ?: "下载失败")
            }
        }
    }

    fun cancelApkDownload() {
        downloadJob?.cancel()
        downloadJob = null
        apkDownload.value = ApkDownloadState.Idle
    }

    fun denyInstallPermission() {
        apkDownload.value = ApkDownloadState.Failed("请允许安装未知应用后再更新")
    }

    fun unknownSourcesIntent(): Intent {
        val app = getApplication<Application>()
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${app.packageName}".toUri(),
        )
    }

    private fun installDownloadedApk(file: File) {
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
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
        val previous = container.settings.snapshot().themeColor
        container.settings.setThemeColor(color)
        if (previous != color) container.timetable.recolorToTheme(previous, color)
        WidgetData.refreshAll(getApplication())
    }

    fun setThemeMode(mode: String) = viewModelScope.launch {
        container.settings.setThemeMode(mode)
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

    fun addSemester(semester: String) = viewModelScope.launch {
        if (semester.isBlank()) return@launch
        container.timetable.ensureSemester(semester)
        if (container.settings.snapshot().selectedSemester != semester) {
            container.settings.setSelectedSemester(semester)
            refreshAlarms()
            WidgetData.refreshAll(getApplication())
        }
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
        runCatching {
            if (text.contains("\"format\"") && text.contains("sysukcb")) {
                container.share.importJson(text)
            } else {
                val result = container.wakeup.import(
                    raw = text,
                    semesterHint = container.settings.snapshot().selectedSemester,
                    themeColor = settings.value.themeColor,
                )
                if (result.semester.isNotBlank()) container.settings.setSelectedSemester(result.semester)
                result.semester
            }
        }
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

    fun importWakeUp(text: String) = viewModelScope.launch {
        importing.value = true
        runCatching {
            container.wakeup.import(
                raw = text,
                semesterHint = container.settings.snapshot().selectedSemester,
                themeColor = settings.value.themeColor,
            )
        }
            .onSuccess {
                if (it.semester.isNotBlank()) container.settings.setSelectedSemester(it.semester)
                message.value = "已从 WakeUp 导入 ${it.count} 门课"
                openTimetableAt.value = System.currentTimeMillis()
                refreshAlarms()
                WidgetData.refreshAll(getApplication())
            }
            .onFailure { message.value = it.message ?: "WakeUp 导入失败" }
        importing.value = false
    }

    fun exportSemester(semester: String) = viewModelScope.launch {
        runCatching {
            val file = container.share.exportSemester(semester)
            container.share.shareFile(file)
        }.onFailure { message.value = it.message ?: "导出失败" }
    }

    fun saveWebDav(
        url: String,
        user: String,
        password: String,
        nickname: String,
        autoSync: Boolean,
    ) = viewModelScope.launch {
        runCatching { persistWebDav(url, user, password, nickname, autoSync) }
            .onSuccess { message.value = "已保存 WebDAV 设置" }
            .onFailure { message.value = it.message ?: "保存失败" }
    }

    fun uploadWebDav(
        url: String,
        user: String,
        password: String,
        nickname: String,
        autoSync: Boolean,
    ) = viewModelScope.launch {
        webdavBusy.value = true
        runCatching {
            persistWebDav(url, user, password, nickname, autoSync)
            container.webdav.upload()
        }
            .onSuccess { message.value = "已上传课表到 WebDAV" }
            .onFailure { message.value = it.message ?: "上传失败" }
        webdavBusy.value = false
    }

    fun downloadWebDav(
        url: String,
        user: String,
        password: String,
        nickname: String,
        autoSync: Boolean,
    ) = viewModelScope.launch {
        webdavBusy.value = true
        importing.value = true
        runCatching {
            persistWebDav(url, user, password, nickname, autoSync)
            container.webdav.download()
        }
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

    fun syncFriendsWebDav(
        url: String,
        user: String,
        password: String,
        nickname: String,
        autoSync: Boolean,
    ) = viewModelScope.launch {
        webdavBusy.value = true
        runCatching {
            persistWebDav(url, user, password, nickname, autoSync)
            container.webdav.syncFriends()
        }
            .onSuccess { message.value = it }
            .onFailure { message.value = it.message ?: "同步好友失败" }
        webdavBusy.value = false
    }

    fun refreshFriends(silent: Boolean = false) = viewModelScope.launch {
        if (webdavBusy.value) return@launch
        webdavBusy.value = true
        runCatching { container.webdav.syncFriends() }
            .onSuccess { if (!silent) message.value = it }
            .onFailure { if (!silent) message.value = it.message ?: "同步好友失败" }
        webdavBusy.value = false
    }

    fun setSelectedFriend(id: String) = viewModelScope.launch {
        container.settings.setSelectedFriendId(id)
    }

    private suspend fun persistWebDav(
        url: String,
        user: String,
        password: String,
        nickname: String,
        autoSync: Boolean,
    ) {
        val raw = url.trim().ifBlank { WebDavClient.DEFAULT_NUTSTORE_FILE_URL }
        val canonical = runCatching { WebDavClient.normalizeFileUrl(raw).toString() }.getOrDefault(raw)
        val nick = nickname.trim().let { value ->
            if (value.isBlank()) "" else WebDavClient.sanitizeNickname(value)
        }
        container.settings.setWebDav(canonical, user.trim(), nick, autoSync)
        if (password.isNotBlank()) container.webdavSecrets.save(password)
        webdavHasPassword.value = container.webdavSecrets.hasPassword()
        WebDavSyncWorker.schedule(getApplication(), autoSync)
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
        container.friends.clear()
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

sealed class ApkDownloadState {
    data object Idle : ApkDownloadState()
    data class Progress(val received: Long, val total: Long) : ApkDownloadState()
    data object Installing : ApkDownloadState()
    data class Failed(val message: String) : ApkDownloadState()
}
