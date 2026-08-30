package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.prefs.WebDavSecrets
import cn.sysu.kcb.data.repo.ShareService

class WebDavSyncService(
    private val client: WebDavClient,
    private val share: ShareService,
    private val settings: SettingsRepository,
    private val secrets: WebDavSecrets,
) {
    suspend fun upload() {
        val snap = settings.snapshot()
        val password = secrets.password()
        if (snap.webdavUrl.isBlank() || snap.webdavUser.isBlank() || password.isBlank()) {
            throw ImportFailedException("请先填写 WebDAV 地址、用户名和密码")
        }
        client.upload(snap.webdavUrl, snap.webdavUser, password, share.exportAllJson())
        settings.setWebDavLastSync(System.currentTimeMillis(), "已上传到云端")
    }

    suspend fun download(): String {
        val snap = settings.snapshot()
        val password = secrets.password()
        if (snap.webdavUrl.isBlank() || snap.webdavUser.isBlank() || password.isBlank()) {
            throw ImportFailedException("请先填写 WebDAV 地址、用户名和密码")
        }
        val json = client.download(snap.webdavUrl, snap.webdavUser, password)
        val semester = share.importJson(json)
        settings.setWebDavLastSync(System.currentTimeMillis(), "已从云端导入")
        return semester
    }
}
