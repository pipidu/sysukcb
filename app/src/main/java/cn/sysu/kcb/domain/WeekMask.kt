package cn.sysu.kcb.domain

import kotlin.math.max
import kotlin.math.min

object WeekMask {
    const val MAX_WEEK = 30

    fun bit(week: Int): Long =
        if (week in 1..62) 1L shl (week - 1) else 0L

    fun has(mask: Long, week: Int): Boolean = mask and bit(week) != 0L

    fun fromRange(start: Int, end: Int, predicate: (Int) -> Boolean = { true }): Long {
        var mask = 0L
        for (week in start..end) {
            if (predicate(week)) mask = mask or bit(week)
        }
        return mask
    }

    fun parse(timeDetail: String, startWeek: Int, maxWeek: Int = MAX_WEEK): Long {
        val raw = timeDetail.replace("/", "").trim()
        if (raw.isEmpty()) return bit(startWeek.coerceAtLeast(1))
        val odd = raw.contains("单")
        val even = raw.contains("双") && !odd
        val cleaned = raw
            .replace("每周", "")
            .replace("单周", "")
            .replace("双周", "")
            .replace("周", "")
            .trim()
        var mask = 0L
        if (cleaned.isEmpty()) {
            mask = fromRange(startWeek.coerceAtLeast(1), maxWeek)
        } else {
            for (part in cleaned.split(",", "，", "、", ";", "；")) {
                val token = part.trim()
                if (token.isEmpty()) continue
                val range = token.split("-", "–", "—", "~")
                if (range.size >= 2) {
                    val a = range[0].filter { it.isDigit() }.toIntOrNull() ?: continue
                    val b = range[1].filter { it.isDigit() }.toIntOrNull() ?: continue
                    mask = mask or fromRange(min(a, b), max(a, b))
                } else {
                    val n = token.filter { it.isDigit() }.toIntOrNull() ?: continue
                    mask = mask or bit(n)
                }
            }
        }
        if (odd) {
            var filtered = 0L
            for (w in 1..maxWeek) if (w % 2 == 1 && has(mask, w)) filtered = filtered or bit(w)
            mask = filtered
        } else if (even) {
            var filtered = 0L
            for (w in 1..maxWeek) if (w % 2 == 0 && has(mask, w)) filtered = filtered or bit(w)
            mask = filtered
        }
        if (mask == 0L) mask = bit(startWeek.coerceAtLeast(1))
        return mask
    }

    fun describe(mask: Long, maxWeek: Int = MAX_WEEK): String {
        val weeks = (1..maxWeek).filter { has(mask, it) }
        if (weeks.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = weeks.first()
        var prev = start
        for (w in weeks.drop(1) + listOf(-1)) {
            if (w == prev + 1) {
                prev = w
            } else {
                parts += if (start == prev) "$start" else "$start-$prev"
                start = w
                prev = w
            }
        }
        return parts.joinToString(",") + "周"
    }
}

fun String.cleanJwxt(): String = trim().trimEnd('/').trim()

object CourseColors {
    const val DEFAULT_THEME = 0xFF8C1A1AL

    private val legacyPalette = listOf(
        0xFFC62828, 0xFFAD1457, 0xFFC2185B, 0xFF880E4F, 0xFF6A1B9A,
        0xFF4527A0, 0xFF512DA8, 0xFF283593, 0xFF1A237E, 0xFF1565C0,
        0xFF0277BD, 0xFF0288D1, 0xFF00838F, 0xFF00695C, 0xFF004D40,
        0xFF2E7D32, 0xFF33691E, 0xFF558B2F, 0xFFEF6C00, 0xFFE65100,
        0xFFD84315, 0xFFBF360C, 0xFF4E342E, 0xFF5D4037, 0xFF6D4C41,
        0xFF455A64, 0xFF37474F, 0xFF263238, 0xFF00897B, 0xFF00796B,
        0xFF5E35B1, 0xFF3949AB,
    )

    // 马卡龙浅色（WakeUp / 小爱同款思路）：色相随主题旋转，浅底深字。
    private data class Swatch(val hue: Float, val s: Float, val l: Float)

    private val macaron = listOf(
        Swatch(4f, 0.58f, 0.72f),
        Swatch(18f, 0.64f, 0.70f),
        Swatch(36f, 0.62f, 0.68f),
        Swatch(148f, 0.40f, 0.68f),
        Swatch(174f, 0.44f, 0.66f),
        Swatch(196f, 0.56f, 0.70f),
        Swatch(220f, 0.52f, 0.68f),
        Swatch(252f, 0.42f, 0.70f),
        Swatch(320f, 0.44f, 0.72f),
        Swatch(340f, 0.54f, 0.70f),
        Swatch(88f, 0.28f, 0.68f),
        Swatch(200f, 0.26f, 0.66f),
    )

    val palette: List<Long> get() = paletteFor(DEFAULT_THEME)

    fun paletteFor(theme: Long): List<Long> {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV((theme and 0xFFFFFFFFL).toInt(), hsv)
        val shift = hsv[0] - macaron.first().hue
        return macaron.map { swatch ->
            val h = (swatch.hue + shift + 360f) % 360f
            hsl(h, swatch.s, swatch.l)
        }
    }

    fun of(name: String, theme: Long = DEFAULT_THEME): Long {
        val pal = paletteFor(theme)
        val index = (name.hashCode().toUInt() % pal.size.toUInt()).toInt()
        return pal[index]
    }

    fun display(stored: Long, name: String, theme: Long): Long {
        val pal = paletteFor(theme)
        if (pal.any { it == stored }) return stored
        return of(name, theme)
    }

    fun remap(stored: Long, fromTheme: Long, toTheme: Long, name: String): Long {
        val dest = paletteFor(toTheme)
        val slot = slotOf(stored, fromTheme, name, dest.size)
        return dest[slot]
    }

    fun ink(bg: Long): Long {
        val c = (bg and 0xFFFFFFFFL).toInt()
        val r = android.graphics.Color.red(c) / 255.0
        val g = android.graphics.Color.green(c) / 255.0
        val b = android.graphics.Color.blue(c) / 255.0
        fun lin(x: Double) = if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        val y = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        return if (y > 0.42) 0xFF2C2C2CL else 0xFFFFFFFFL
    }

    private fun slotOf(stored: Long, fromTheme: Long, name: String, destSize: Int): Int {
        val themed = paletteFor(fromTheme)
        themed.indexOf(stored).takeIf { it >= 0 }?.let { return it % destSize }
        legacyPalette.indexOf(stored).takeIf { it >= 0 }?.let { return it % destSize }
        return (name.hashCode().toUInt() % destSize.toUInt()).toInt()
    }

    private fun hsl(h: Float, s: Float, l: Float): Long {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = ((h % 360f + 360f) % 360f) / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

object DefaultPeriods {
    data class Period(val section: Int, val name: String, val start: String, val end: String, val big: String, val bigName: String)

    val list = listOf(
        Period(1, "第1节", "08:00", "08:45", "1", "第1大节"),
        Period(2, "第2节", "08:55", "09:40", "1", "第1大节"),
        Period(3, "第3节", "10:10", "10:55", "2", "第2大节"),
        Period(4, "第4节", "11:05", "11:50", "2", "第2大节"),
        Period(5, "第5节", "14:20", "15:05", "4", "第3大节"),
        Period(6, "第6节", "15:15", "16:00", "4", "第3大节"),
        Period(7, "第7节", "16:30", "17:15", "5", "第4大节"),
        Period(8, "第8节", "17:25", "18:10", "5", "第4大节"),
        Period(9, "第9节", "19:00", "19:45", "6", "第5大节"),
        Period(10, "第10节", "19:55", "20:40", "6", "第5大节"),
        Period(11, "第11节", "20:50", "21:35", "6", "第5大节"),
    )
}
