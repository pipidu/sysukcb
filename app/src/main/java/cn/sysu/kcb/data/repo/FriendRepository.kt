package cn.sysu.kcb.data.repo

import androidx.room.withTransaction
import cn.sysu.kcb.data.local.AppDatabase
import cn.sysu.kcb.data.local.FriendPackEntity
import kotlinx.coroutines.flow.Flow

class FriendRepository(
    private val db: AppDatabase,
    private val share: ShareService,
) {
    fun observe(): Flow<List<FriendPackEntity>> = db.friendPackDao().observeAll()

    suspend fun list(): List<FriendPackEntity> = db.friendPackDao().list()

    fun decode(entity: FriendPackEntity): SharePack = share.decodePack(entity.payload)

    suspend fun upsert(item: FriendPackEntity) {
        db.friendPackDao().upsert(item)
    }

    suspend fun keepOnly(ids: Collection<String>) {
        val dao = db.friendPackDao()
        if (ids.isEmpty()) {
            dao.clear()
        } else {
            dao.deleteNotIn(ids.toList())
        }
    }

    suspend fun replaceAll(items: List<FriendPackEntity>) {
        db.withTransaction {
            db.friendPackDao().clear()
            items.forEach { db.friendPackDao().upsert(it) }
        }
    }

    suspend fun clear() {
        db.friendPackDao().clear()
    }
}
