package cn.sysu.kcb.ui.motion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import kotlin.math.roundToInt

/**
 * ColorOS 16.1+ View 无缝动画（卡片 ⇄ 全屏 Activity）。
 * 系统以 compileOnly AAR 提供 [com.oplus.animation.OplusViewSeamless]，
 * 不在 Maven Central，运行时用反射调用；其它机型退回普通 startActivity。
 */
data class SeamlessSource(
    val view: View,
    val screenRect: Rect,
    val radiusPx: Float,
    val fillColor: Int,
)

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

fun Activity.startWithOplusViewSeamless(intent: Intent, source: SeamlessSource?): Boolean {
    val options = source?.let { OplusViewSeamlessBridge.prepare(it) }
    return try {
        if (options != null) {
            startActivity(intent, options)
            true
        } else {
            startActivity(intent)
            false
        }
    } catch (_: Throwable) {
        startActivity(intent)
        false
    }
}

fun LayoutCoordinates.toScreenRect(host: View): Rect {
    val bounds = boundsInWindow()
    val screen = IntArray(2)
    val window = IntArray(2)
    host.getLocationOnScreen(screen)
    host.getLocationInWindow(window)
    val dx = screen[0] - window[0]
    val dy = screen[1] - window[1]
    return Rect(
        (bounds.left + dx).roundToInt(),
        (bounds.top + dy).roundToInt(),
        (bounds.right + dx).roundToInt(),
        (bounds.bottom + dy).roundToInt(),
    )
}

internal object OplusViewSeamlessBridge {
    private const val CLASS = "com.oplus.animation.OplusViewSeamless"
    private const val OS_16_0_BASE_FALLBACK = 37001

    fun prepare(source: SeamlessSource): Bundle? {
        return try {
            val clazz = Class.forName(CLASS)
            val version = runCatching {
                clazz.getMethod("getVersion").invoke(null) as Int
            }.getOrDefault(Int.MAX_VALUE)
            val base = runCatching {
                clazz.getField("OS_16_0_BASE").getInt(null)
            }.getOrDefault(OS_16_0_BASE_FALLBACK)
            if (version <= base) return null

            val bundle = Bundle()
            bundle.putBoolean(stringConst(clazz, "VIEW_SEAMLESS_OPEN"), true)
            bundle.putFloat(stringConst(clazz, "BUNDLE_RADIUS"), source.radiusPx)
            if (source.fillColor != -1) {
                bundle.putInt(stringConst(clazz, "BUNDLE_COLOR"), source.fillColor)
            }
            bundle.putParcelable(stringConst(clazz, "BUNDLE_RECT"), source.screenRect)
            captureCard(source)?.let { bitmap ->
                runCatching {
                    bundle.putParcelable(stringConst(clazz, "BUNDLE_BITMAP"), bitmap)
                }
            }

            val set = clazz.methods.firstOrNull {
                it.name == "setSeamlessView" && it.parameterTypes.size == 4
            } ?: return null
            val context = source.view.context.findActivity() ?: return null
            val ok = set.invoke(null, source.view, context, bundle, null) as? Boolean ?: false
            if (ok) bundle else null
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoSuchMethodError) {
            null
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun stringConst(clazz: Class<*>, name: String): String =
        clazz.getField(name).get(null) as String

    private fun captureCard(source: SeamlessSource): Bitmap? {
        return try {
            val host = source.view
            if (host.width <= 0 || host.height <= 0) return null
            val screen = IntArray(2)
            host.getLocationOnScreen(screen)
            val left = (source.screenRect.left - screen[0]).coerceIn(0, host.width - 1)
            val top = (source.screenRect.top - screen[1]).coerceIn(0, host.height - 1)
            val right = (source.screenRect.right - screen[0]).coerceIn(left + 1, host.width)
            val bottom = (source.screenRect.bottom - screen[1]).coerceIn(top + 1, host.height)
            val full = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
            host.draw(Canvas(full))
            val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            if (cropped !== full) full.recycle()
            cropped
        } catch (_: Throwable) {
            null
        }
    }
}
