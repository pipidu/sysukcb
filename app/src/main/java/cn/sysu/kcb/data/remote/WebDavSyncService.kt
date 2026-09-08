package cn.sysu.kcb.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cn.sysu.kcb.data.local.FriendPackEntity
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.prefs.WebDavSecrets
import cn.sysu.kcb.data.repo.FriendRepository
import cn.sysu.kcb.data.repo.ShareService
import kotlinx.coroutines.CancellationException

class WebDavSyncService(
    private val context: Context,
    private val client: WebDavClient,
    private val share: ShareService,
    private val settings: SettingsRepository,
    private val secrets: WebDavSecrets,
    private val friends: FriendRepository,
) {
    suspend fun upload() {
        val creds = requireCreds()
        val body = share.exportAllJson(creds.nickname)
        if (creds.nickname.isNotBlank()) {
            ensureNicknameAvailable(creds.url, creds.user, creds.password, creds.nickname)
        }
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
        if (snap.webdavWifiOnly && !isOnWifi(context)) return null
        val now = System.currentTimeMillis()
        if (!force && snap.webdavLastSyncAt > 0L && now - snap.webdavLastSyncAt < MIN_AUTO_INTERVAL_MS) {
            return snap.webdavLastMessage
        }
        return try {
            val creds = requireCreds(needNickname = false)
            val body = share.exportAllJson(creds.nickname)
            if (creds.nickname.isNotBlank()) {
                ensureNicknameAvailable(creds.url, creds.user, creds.password, creds.nickname)
            }
            client.upload(creds.url, creds.user, creds.password, body)
            if (creds.nickname.isNotBlank()) {
                pullFriends(creds, body)
            } else {
                settings.setWebDavLastSync(now, "已自动上传到云端")
                "已自动上传到云端"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching {
                settings.setWebDavLastSync(now, e.message ?: "自动同步失败")
            }
            throw e
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
        val existing = friends.list().associateBy { it.id }
        val kept = mutableListOf<String>()
        val now = System.currentTimeMillis()
        for (filename in remote) {
            val id = WebDavClient.stemOf(filename)
            if (id.isBlank()) continue
            val fileUrl = WebDavClient.fileInDirectory(ownUrl, filename).toString()
            val json = runCatching {
                client.download(fileUrl, creds.user, creds.password)
            }.getOrNull()
            val pack = json?.let { runCatching { share.decodePack(it) }.getOrNull() }
            if (pack != null) {
                friends.upsert(
                    FriendPackEntity(
                        id = id,
                        nickname = pack.nickname.ifBlank { id },
                        filename = filename,
                        payload = json,
                        exportedAt = pack.exportedAt,
                        syncedAt = now,
                    ),
                )
                kept += id
            } else {
                existing[id]?.let { kept += it.id }
            }
        }
        friends.keepOnly(kept)
        val message = when (kept.size) {
            0 -> "已同步，暂无其他好友课表"
            else -> "已同步 ${kept.size} 位好友"
        }
        settings.setWebDavLastSync(now, message)
        return message
    }

    suspend fun ensureNicknameAvailable(
        url: String,
        user: String,
        password: String,
        nickname: String,
    ) {
        if (nickname.isBlank()) return
        val nick = WebDavClient.sanitizeNickname(nickname)
        val lastUploaded = settings.snapshot().webdavLastUploadedNickname
        if (lastUploaded.isNotBlank() && lastUploaded.equals(nick, ignoreCase = true)) return
        val filename = WebDavClient.nicknameFilename(nick)
        val files = client.listJsonFiles(url, user, password, missingAsEmpty = true)
        if (files.any { it.equals(filename, ignoreCase = true) }) {
            throw ImportFailedException(NICKNAME_TAKEN)
        }
    }

    private suspend fun uploadNicknameFile(creds: Creds, body: String) {
        ensureNicknameAvailable(creds.url, creds.user, creds.password, creds.nickname)
        removeStaleNicknameFiles(creds)
        val url = WebDavClient.fileInDirectory(
            WebDavClient.normalizeFileUrl(creds.url),
            WebDavClient.nicknameFilename(creds.nickname),
        ).toString()
        client.upload(url, creds.user, creds.password, body)
        settings.setWebDavLastUploadedNickname(creds.nickname)
    }

    private suspend fun removeStaleNicknameFiles(creds: Creds) {
        val lastUploaded = settings.snapshot().webdavLastUploadedNickname
        if (lastUploaded.isBlank() || lastUploaded.equals(creds.nickname, ignoreCase = true)) return
        val nick = runCatching { WebDavClient.sanitizeNickname(lastUploaded) }.getOrNull() ?: return
        if (nick.equals(creds.nickname, ignoreCase = true)) return
        val filename = WebDavClient.nicknameFilename(nick)
        val fileUrl = WebDavClient.fileInDirectory(
            WebDavClient.normalizeFileUrl(creds.url),
            filename,
        ).toString()
        runCatching { client.delete(fileUrl, creds.user, creds.password) }
        friends.deleteById(WebDavClient.stemOf(filename))
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
        const val NICKNAME_TAKEN = "昵称已存在，请更换"
        private const val MIN_AUTO_INTERVAL_MS = 10 * 60 * 1000L

        fun isOnWifi(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }
}
