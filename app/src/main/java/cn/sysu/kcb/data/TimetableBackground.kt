package cn.sysu.kcb.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object TimetableBackground {
    const val FILE_NAME = "timetable_bg.jpg"
    private const val MAX_IMPORT_SIDE = 1600

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    suspend fun importUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "timetable_bg_in")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!copied || !tmp.isFile) {
            tmp.delete()
            return@withContext false
        }
        val decoded = decodeFile(tmp, MAX_IMPORT_SIDE)
        tmp.delete()
        if (decoded == null) return@withContext false
        val dest = file(context)
        val ok = dest.outputStream().use { out ->
            decoded.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        decoded.recycle()
        ok
    }

    suspend fun decode(context: Context, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val target = file(context)
        if (!target.isFile) return@withContext null
        decodeFile(target, maxSide.coerceIn(240, 2048))
    }

    fun clear(context: Context) {
        file(context).delete()
        File(context.cacheDir, "timetable_bg_in").delete()
    }

    private fun decodeFile(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}
