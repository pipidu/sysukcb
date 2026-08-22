package cn.sysu.kcb.data

import android.content.Context
import cn.sysu.kcb.data.local.AppDatabase
import cn.sysu.kcb.data.prefs.CookieStore
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.remote.JwxtImportService
import cn.sysu.kcb.data.remote.createJwxtApi
import cn.sysu.kcb.data.remote.createJwxtJson
import cn.sysu.kcb.data.repo.ShareService
import cn.sysu.kcb.data.repo.TimetableRepository
import cn.sysu.kcb.notify.ClassAlarmScheduler

class AppContainer(context: Context) {
    val json = createJwxtJson()
    val db = AppDatabase.create(context)
    val cookies = CookieStore(context)
    val settings = SettingsRepository(context)
    val timetable = TimetableRepository(db)
    val api = createJwxtApi(cookies, json)
    val importer = JwxtImportService(api, json, cookies, timetable, settings)
    val share = ShareService(context, timetable, json)
    val alarms = ClassAlarmScheduler(context)
}
