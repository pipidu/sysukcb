package cn.sysu.kcb.data

import android.content.Context
import cn.sysu.kcb.data.local.AppDatabase
import cn.sysu.kcb.data.prefs.CookieStore
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.prefs.WebDavSecrets
import cn.sysu.kcb.data.remote.GithubUpdateService
import cn.sysu.kcb.data.remote.GzhuClient
import cn.sysu.kcb.data.remote.GzhuImportService
import cn.sysu.kcb.data.remote.ImportRouter
import cn.sysu.kcb.data.remote.JwxtImportService
import cn.sysu.kcb.data.remote.WebDavClient
import cn.sysu.kcb.data.remote.WebDavSyncService
import cn.sysu.kcb.data.remote.WakeUpImportService
import cn.sysu.kcb.data.remote.createJwxtApi
import cn.sysu.kcb.data.remote.createJwxtJson
import cn.sysu.kcb.data.repo.FriendRepository
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
    private val sysuImporter = JwxtImportService(api, json, cookies, timetable, settings)
    private val gzhuImporter = GzhuImportService(GzhuClient(cookies), json, cookies, timetable, settings)
    val importer = ImportRouter(settings, sysuImporter, gzhuImporter)
    val updates = GithubUpdateService(json)
    val share = ShareService(context, timetable, json)
    val webdavSecrets = WebDavSecrets(context)
    val friends = FriendRepository(db, share)
    val wakeup = WakeUpImportService(timetable, json)
    val webdav = WebDavSyncService(WebDavClient(), share, settings, webdavSecrets, friends)
    val alarms = ClassAlarmScheduler(context)
}
