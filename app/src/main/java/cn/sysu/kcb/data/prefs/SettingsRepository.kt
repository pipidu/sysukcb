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
    val reminderEnabled: Boolean = true,
    val reminderMinutes: Int = 15,
    val examReminderEnabled: Boolean = true,
    val examReminderMinutes: Int = 60,
    val selectedSemester: String = "",
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<UserSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun snapshot(): UserSettings = context.dataStore.data.map { it.toSettings() }.first()

    suspend fun setThemeColor(color: Long) {
        context.dataStore.edit { it[Keys.themeColor] = color }
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

    private fun Preferences.toSettings() = UserSettings(
        themeColor = this[Keys.themeColor] ?: SettingsRepository.DEFAULT_THEME_COLOR,
        reminderEnabled = this[Keys.reminderEnabled] ?: true,
        reminderMinutes = this[Keys.reminderMinutes] ?: 15,
        examReminderEnabled = this[Keys.examReminderEnabled] ?: true,
        examReminderMinutes = this[Keys.examReminderMinutes] ?: 60,
        selectedSemester = this[Keys.selectedSemester].orEmpty(),
    )

    private object Keys {
        val themeColor = longPreferencesKey("theme_color")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderMinutes = intPreferencesKey("reminder_minutes")
        val examReminderEnabled = booleanPreferencesKey("exam_reminder_enabled")
        val examReminderMinutes = intPreferencesKey("exam_reminder_minutes")
        val selectedSemester = stringPreferencesKey("selected_semester")
    }

    companion object {
        const val DEFAULT_THEME_COLOR = 0xFF8C1A1AL
    }
}
