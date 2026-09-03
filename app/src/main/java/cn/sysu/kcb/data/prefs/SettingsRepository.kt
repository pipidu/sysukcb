package cn.sysu.kcb.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("kcb_settings")

data class UserSettings(
    val themeColor: Long = SettingsRepository.DEFAULT_THEME_COLOR,
    val themeMode: String = SettingsRepository.THEME_MODE_SYSTEM,
    val reminderEnabled: Boolean = true,
    val reminderMinutes: Int = 15,
    val examReminderEnabled: Boolean = true,
    val examReminderMinutes: Int = 60,
    val selectedSemester: String = "",
    val schoolId: String = SettingsRepository.DEFAULT_SCHOOL_ID,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavNickname: String = "",
    val webdavAutoSync: Boolean = true,
    val webdavWifiOnly: Boolean = true,
    val webdavLastSyncAt: Long = 0L,
    val webdavLastMessage: String = "",
    val updateUseMirror: Boolean = false,
    val selectedFriendId: String = "",
    val periodHeightDp: Int = SettingsRepository.DEFAULT_PERIOD_HEIGHT_DP,
    val friendPeriodHeightDp: Int = SettingsRepository.DEFAULT_PERIOD_HEIGHT_DP,
    val todayHighlightEnabled: Boolean = true,
    val todayHighlightColor: Long = 0L,
    val todayHighlightAlpha: Int = SettingsRepository.DEFAULT_TODAY_HIGHLIGHT_ALPHA,
    val todayHighlightBarDp: Int = SettingsRepository.DEFAULT_TODAY_HIGHLIGHT_BAR_DP,
    val periodHighlightEnabled: Boolean = true,
    val periodHighlightColor: Long = 0L,
    val periodHighlightAlpha: Int = SettingsRepository.DEFAULT_TODAY_HIGHLIGHT_ALPHA,
    val periodHighlightBarDp: Int = SettingsRepository.DEFAULT_TODAY_HIGHLIGHT_BAR_DP,
    val timetableBgColor: Long = 0L,
    val timetableBgImageRev: Long = 0L,
    val timetableBgDim: Int = SettingsRepository.DEFAULT_TIMETABLE_BG_DIM,
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<UserSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun snapshot(): UserSettings = context.dataStore.data.map { it.toSettings() }.first()

    suspend fun ensureFriendPeriodHeight() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.friendPeriodHeightDp] == null) {
                prefs[Keys.friendPeriodHeightDp] = (
                    prefs[Keys.periodHeightDp] ?: DEFAULT_PERIOD_HEIGHT_DP
                    ).coerceIn(MIN_PERIOD_HEIGHT_DP, MAX_PERIOD_HEIGHT_DP)
            }
        }
    }

    suspend fun setThemeColor(color: Long) {
        context.dataStore.edit { it[Keys.themeColor] = color }
    }

    suspend fun setThemeMode(mode: String) {
        val normalized = when (mode) {
            THEME_MODE_LIGHT, THEME_MODE_DARK -> mode
            else -> THEME_MODE_SYSTEM
        }
        context.dataStore.edit { it[Keys.themeMode] = normalized }
    }

    suspend fun setReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.reminderEnabled] = enabled }
    }

    suspend fun setReminderMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.reminderMinutes] = minutes }
    }

    suspend fun setExamReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.examReminderEnabled] = enabled }
    }

    suspend fun setExamReminderMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.examReminderMinutes] = minutes }
    }

    suspend fun setSelectedSemester(semester: String) {
        context.dataStore.edit { it[Keys.selectedSemester] = semester }
    }

    suspend fun setSchoolId(schoolId: String) {
        context.dataStore.edit { it[Keys.schoolId] = schoolId }
    }

    suspend fun setWebDav(url: String, user: String, nickname: String, autoSync: Boolean) {
        context.dataStore.edit {
            it[Keys.webdavUrl] = url.trim()
            it[Keys.webdavUser] = user.trim()
            it[Keys.webdavNickname] = nickname.trim()
            it[Keys.webdavAutoSync] = autoSync
        }
    }

    suspend fun setWebDavAutoSync(enabled: Boolean) {
        context.dataStore.edit { it[Keys.webdavAutoSync] = enabled }
    }

    suspend fun setWebDavWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.webdavWifiOnly] = enabled }
    }

    suspend fun setUpdateUseMirror(enabled: Boolean) {
        context.dataStore.edit { it[Keys.updateUseMirror] = enabled }
    }

    suspend fun setSelectedFriendId(id: String) {
        context.dataStore.edit { it[Keys.selectedFriendId] = id }
    }

    suspend fun setPeriodHeightDp(dp: Int) {
        context.dataStore.edit { it[Keys.periodHeightDp] = dp.coerceIn(MIN_PERIOD_HEIGHT_DP, MAX_PERIOD_HEIGHT_DP) }
    }

    suspend fun setFriendPeriodHeightDp(dp: Int) {
        context.dataStore.edit {
            it[Keys.friendPeriodHeightDp] = dp.coerceIn(MIN_PERIOD_HEIGHT_DP, MAX_PERIOD_HEIGHT_DP)
        }
    }

    suspend fun setTodayHighlightEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.todayHighlightEnabled] = enabled }
    }

    suspend fun setTodayHighlightColor(color: Long) {
        context.dataStore.edit { it[Keys.todayHighlightColor] = color }
    }

    suspend fun setTodayHighlightAlpha(percent: Int) {
        context.dataStore.edit {
            it[Keys.todayHighlightAlpha] = percent.coerceIn(MIN_TODAY_HIGHLIGHT_ALPHA, MAX_TODAY_HIGHLIGHT_ALPHA)
        }
    }

    suspend fun setTodayHighlightBarDp(dp: Int) {
        context.dataStore.edit {
            it[Keys.todayHighlightBarDp] = dp.coerceIn(MIN_TODAY_HIGHLIGHT_BAR_DP, MAX_TODAY_HIGHLIGHT_BAR_DP)
        }
    }

    suspend fun setPeriodHighlightEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.periodHighlightEnabled] = enabled }
    }

    suspend fun setPeriodHighlightColor(color: Long) {
        context.dataStore.edit { it[Keys.periodHighlightColor] = color }
    }

    suspend fun setPeriodHighlightAlpha(percent: Int) {
        context.dataStore.edit {
            it[Keys.periodHighlightAlpha] = percent.coerceIn(MIN_TODAY_HIGHLIGHT_ALPHA, MAX_TODAY_HIGHLIGHT_ALPHA)
        }
    }

    suspend fun setPeriodHighlightBarDp(dp: Int) {
        context.dataStore.edit {
            it[Keys.periodHighlightBarDp] = dp.coerceIn(MIN_TODAY_HIGHLIGHT_BAR_DP, MAX_TODAY_HIGHLIGHT_BAR_DP)
        }
    }

    suspend fun setTimetableBgColor(color: Long) {
        context.dataStore.edit { it[Keys.timetableBgColor] = color }
    }

    suspend fun setTimetableBgImageRev(rev: Long) {
        context.dataStore.edit { it[Keys.timetableBgImageRev] = rev }
    }

    suspend fun setTimetableBgDim(percent: Int) {
        context.dataStore.edit {
            it[Keys.timetableBgDim] = percent.coerceIn(MIN_TIMETABLE_BG_DIM, MAX_TIMETABLE_BG_DIM)
        }
    }

    suspend fun setWebDavLastSync(at: Long, message: String) {
        context.dataStore.edit {
            it[Keys.webdavLastSyncAt] = at
            it[Keys.webdavLastMessage] = message
        }
    }

    private fun Preferences.toSettings() = UserSettings(
        themeColor = this[Keys.themeColor] ?: SettingsRepository.DEFAULT_THEME_COLOR,
        themeMode = this[Keys.themeMode] ?: SettingsRepository.THEME_MODE_SYSTEM,
        reminderEnabled = this[Keys.reminderEnabled] ?: true,
        reminderMinutes = this[Keys.reminderMinutes] ?: 15,
        examReminderEnabled = this[Keys.examReminderEnabled] ?: true,
        examReminderMinutes = this[Keys.examReminderMinutes] ?: 60,
        selectedSemester = this[Keys.selectedSemester].orEmpty(),
        schoolId = this[Keys.schoolId] ?: SettingsRepository.DEFAULT_SCHOOL_ID,
        webdavUrl = this[Keys.webdavUrl].orEmpty(),
        webdavUser = this[Keys.webdavUser].orEmpty(),
        webdavNickname = this[Keys.webdavNickname].orEmpty(),
        webdavAutoSync = this[Keys.webdavAutoSync] ?: true,
        webdavWifiOnly = this[Keys.webdavWifiOnly] ?: true,
        webdavLastSyncAt = this[Keys.webdavLastSyncAt] ?: 0L,
        webdavLastMessage = this[Keys.webdavLastMessage].orEmpty(),
        updateUseMirror = this[Keys.updateUseMirror] ?: false,
        selectedFriendId = this[Keys.selectedFriendId].orEmpty(),
        periodHeightDp = (this[Keys.periodHeightDp] ?: SettingsRepository.DEFAULT_PERIOD_HEIGHT_DP)
            .coerceIn(SettingsRepository.MIN_PERIOD_HEIGHT_DP, SettingsRepository.MAX_PERIOD_HEIGHT_DP),
        friendPeriodHeightDp = (this[Keys.friendPeriodHeightDp] ?: SettingsRepository.DEFAULT_PERIOD_HEIGHT_DP)
            .coerceIn(SettingsRepository.MIN_PERIOD_HEIGHT_DP, SettingsRepository.MAX_PERIOD_HEIGHT_DP),
        todayHighlightEnabled = this[Keys.todayHighlightEnabled] ?: true,
        todayHighlightColor = this[Keys.todayHighlightColor] ?: 0L,
        todayHighlightAlpha = (this[Keys.todayHighlightAlpha] ?: SettingsRepository.DEFAULT_TODAY_HIGHLIGHT_ALPHA)
            .coerceIn(SettingsRepository.MIN_TODAY_HIGHLIGHT_ALPHA, SettingsRepository.MAX_TODAY_HIGHLIGHT_ALPHA),
        todayHighlightBarDp = (this[Keys.todayHighlightBarDp] ?: SettingsRepository.DEFAULT_TODAY_HIGHLIGHT_BAR_DP)
            .coerceIn(SettingsRepository.MIN_TODAY_HIGHLIGHT_BAR_DP, SettingsRepository.MAX_TODAY_HIGHLIGHT_BAR_DP),
        periodHighlightEnabled = this[Keys.periodHighlightEnabled] ?: true,
        periodHighlightColor = this[Keys.periodHighlightColor] ?: 0L,
        periodHighlightAlpha = (this[Keys.periodHighlightAlpha] ?: SettingsRepository.DEFAULT_TODAY_HIGHLIGHT_ALPHA)
            .coerceIn(SettingsRepository.MIN_TODAY_HIGHLIGHT_ALPHA, SettingsRepository.MAX_TODAY_HIGHLIGHT_ALPHA),
        periodHighlightBarDp = (this[Keys.periodHighlightBarDp] ?: SettingsRepository.DEFAULT_TODAY_HIGHLIGHT_BAR_DP)
            .coerceIn(SettingsRepository.MIN_TODAY_HIGHLIGHT_BAR_DP, SettingsRepository.MAX_TODAY_HIGHLIGHT_BAR_DP),
        timetableBgColor = this[Keys.timetableBgColor] ?: 0L,
        timetableBgImageRev = this[Keys.timetableBgImageRev] ?: 0L,
        timetableBgDim = (this[Keys.timetableBgDim] ?: SettingsRepository.DEFAULT_TIMETABLE_BG_DIM)
            .coerceIn(SettingsRepository.MIN_TIMETABLE_BG_DIM, SettingsRepository.MAX_TIMETABLE_BG_DIM),
    )

    private object Keys {
        val themeColor = longPreferencesKey("theme_color")
        val themeMode = stringPreferencesKey("theme_mode")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderMinutes = intPreferencesKey("reminder_minutes")
        val examReminderEnabled = booleanPreferencesKey("exam_reminder_enabled")
        val examReminderMinutes = intPreferencesKey("exam_reminder_minutes")
        val selectedSemester = stringPreferencesKey("selected_semester")
        val schoolId = stringPreferencesKey("school_id")
        val webdavUrl = stringPreferencesKey("webdav_url")
        val webdavUser = stringPreferencesKey("webdav_user")
        val webdavNickname = stringPreferencesKey("webdav_nickname")
        val webdavAutoSync = booleanPreferencesKey("webdav_auto_sync")
        val webdavWifiOnly = booleanPreferencesKey("webdav_wifi_only")
        val webdavLastSyncAt = longPreferencesKey("webdav_last_sync_at")
        val webdavLastMessage = stringPreferencesKey("webdav_last_message")
        val updateUseMirror = booleanPreferencesKey("update_use_mirror")
        val selectedFriendId = stringPreferencesKey("selected_friend_id")
        val periodHeightDp = intPreferencesKey("period_height_dp")
        val friendPeriodHeightDp = intPreferencesKey("friend_period_height_dp")
        val todayHighlightEnabled = booleanPreferencesKey("today_highlight_enabled")
        val todayHighlightColor = longPreferencesKey("today_highlight_color")
        val todayHighlightAlpha = intPreferencesKey("today_highlight_alpha")
        val todayHighlightBarDp = intPreferencesKey("today_highlight_bar_dp")
        val periodHighlightEnabled = booleanPreferencesKey("period_highlight_enabled")
        val periodHighlightColor = longPreferencesKey("period_highlight_color")
        val periodHighlightAlpha = intPreferencesKey("period_highlight_alpha")
        val periodHighlightBarDp = intPreferencesKey("period_highlight_bar_dp")
        val timetableBgColor = longPreferencesKey("timetable_bg_color")
        val timetableBgImageRev = longPreferencesKey("timetable_bg_image_rev")
        val timetableBgDim = intPreferencesKey("timetable_bg_dim")
    }

    companion object {
        const val DEFAULT_THEME_COLOR = 0xFF8C1A1AL
        const val DEFAULT_SCHOOL_ID = "sysu"
        const val THEME_MODE_SYSTEM = "system"
        const val THEME_MODE_LIGHT = "light"
        const val THEME_MODE_DARK = "dark"
        const val DEFAULT_PERIOD_HEIGHT_DP = 58
        const val MIN_PERIOD_HEIGHT_DP = 40
        const val MAX_PERIOD_HEIGHT_DP = 88
        const val DEFAULT_TODAY_HIGHLIGHT_ALPHA = 22
        const val MIN_TODAY_HIGHLIGHT_ALPHA = 8
        const val MAX_TODAY_HIGHLIGHT_ALPHA = 50
        const val DEFAULT_TODAY_HIGHLIGHT_BAR_DP = 3
        const val MIN_TODAY_HIGHLIGHT_BAR_DP = 0
        const val MAX_TODAY_HIGHLIGHT_BAR_DP = 8
        const val DEFAULT_TIMETABLE_BG_DIM = 24
        const val MIN_TIMETABLE_BG_DIM = 0
        const val MAX_TIMETABLE_BG_DIM = 60
    }
}
