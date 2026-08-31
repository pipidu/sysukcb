package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.local.FriendPackEntity
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.prefs.WebDavSecrets
import cn.sysu.kcb.data.repo.FriendRepository
import cn.sysu.kcb.data.repo.ShareService

class WebDavSyncService(
    private val client: WebDavClient,
    private val share: ShareService,
    private val settings: SettingsRepository,
    private val secrets: WebDavSecrets,
    private val friends: FriendRepository,
) {
    suspend fun upload() {
        val creds = requireCreds()
        val body = share.exportAllJson(creds.nickname)
        client.upload(creds.url, creds.user, creds.password, body)
        if (creds.nickname.isNotBlank()) {
            uploadNicknameFile(creds, body)
        }
        settings.setWebDavLastSync(System.currentTimeMillis(), "已上传到云端")
    }

    suspend fun download(): String {
        val creds = requireCreds()
        val json = client.download(creds.url, creds.user, creds.password)
        val semester = share.importJson(json)
        settings.setWebDavLastSync(System.currentTimeMillis(), "已从云端导入")
        return semester
    }

    suspend fun syncFriends(): String {
        val creds = requireCreds(needNickname = true)
        return pullFriends(creds, share.exportAllJson(creds.nickname))
    }

    suspend fun autoSync(force: Boolean = false): String? {
        val snap = settings.snapshot()
        if (!snap.webdavAutoSync) return null
        if (snap.webdavUrl.isBlank() || snap.webdavUser.isBlank() || secrets.password().isBlank()) return null
        val now = System.currentTimeMillis()
        if (!force && snap.webdavLastSyncAt > 0L && now - snap.webdavLastSyncAt < MIN_AUTO_INTERVAL_MS) {
            return snap.webdavLastMessage
        }
        val creds = requireCreds(needNickname = false)
        val body = share.exportAllJson(creds.nickname)
        client.upload(creds.url, creds.user, creds.password, body)
        return if (creds.nickname.isNotBlank()) {
            pullFriends(creds, body)
        } else {
            settings.setWebDavLastSync(now, "已自动上传到云端")
            "已自动上传到云端"
        }
    }

    private suspend fun pullFriends(creds: Creds, ownBody: String): String {
        uploadNicknameFile(creds, ownBody)
        val files = client.listJsonFiles(creds.url, creds.user, creds.password)
        val ownName = WebDavClient.nicknameFilename(creds.nickname)
        val ownUrl = WebDavClient.normalizeFileUrl(creds.url)
        val ownBackup = ownUrl.pathSegments.lastOrNull().orEmpty()
        val remote = files.filter { name ->
            !name.equals(ownName, ignoreCase = true) &&
                !name.equals(ownBackup, ignoreCase = true) &&
                !name.equals(WebDavClient.DEFAULT_FILE, ignoreCase = true)
        }
        val kept = mutableListOf<String>()
        val now = System.currentTimeMillis()
        for (filename in remote) {
            val id = WebDavClient.stemOf(filename)
            if (id.isBlank()) continue
            val fileUrl = WebDavClient.fileInDirectory(ownUrl, filename).toString()
            val json = runCatching {
                client.download(fileUrl, creds.user, creds.password)
            }.getOrNull() ?: continue
            val pack = runCatching { share.decodePack(json) }.getOrNull() ?: continue
            val nickname = pack.nickname.ifBlank { id }
            friends.upsert(
                FriendPackEntity(
                    id = id,
                    nickname = nickname,
                    filename = filename,
                    payload = json,
                    exportedAt = pack.exportedAt,
                    syncedAt = now,
                ),
            )
            kept += id
        }
        friends.keepOnly(kept)
        val message = when (kept.size) {
            0 -> "已同步，暂无其他好友课表"
            else -> "已同步 ${kept.size} 位好友"
        }
        settings.setWebDavLastSync(now, message)
        return message
    }

    private suspend fun uploadNicknameFile(creds: Creds, body: String) {
        val url = WebDavClient.fileInDirectory(
            WebDavClient.normalizeFileUrl(creds.url),
            WebDavClient.nicknameFilename(creds.nickname),
        ).toString()
        client.upload(url, creds.user, creds.password, body)
    }

    private suspend fun requireCreds(needNickname: Boolean = false): Creds {
        val snap = settings.snapshot()
        val password = secrets.password()
        if (snap.webdavUrl.isBlank() || snap.webdavUser.isBlank() || password.isBlank()) {
            throw ImportFailedException("请先填写 WebDAV 地址、用户名和密码")
        }
        val nickname = if (needNickname || snap.webdavNickname.isNotBlank()) {
            WebDavClient.sanitizeNickname(snap.webdavNickname)
        } else {
            ""
        }
        return Creds(snap.webdavUrl, snap.webdavUser, password, nickname)
    }

    private data class Creds(
        val url: String,
        val user: String,
        val password: String,
        val nickname: String,
    )

    companion object {
        private const val MIN_AUTO_INTERVAL_MS = 10 * 60 * 1000L
    }
}
