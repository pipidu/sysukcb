package cn.sysu.kcb.domain

import kotlin.math.max
import kotlin.math.min

object WeekMask {
    const val MAX_WEEK = 30

    fun bit(week: Int): Long =
        if (week in 1..62) 1L shl (week - 1) else 0L

    fun has(mask: Long, week: Int): Boolean = mask and bit(week) != 0L

    /** 0 是旧便签未设周次，按每周都显示。 */
    fun showsOn(mask: Long, week: Int): Boolean = mask == 0L || has(mask, week)

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
        var mask = 0L
        for (part in raw.split(",", "，", "、", ";", "；")) {
            mask = mask or parsePart(part, startWeek, maxWeek)
        }
        if (mask == 0L) mask = bit(startWeek.coerceAtLeast(1))
        return mask
    }

    private fun parsePart(part: String, startWeek: Int, maxWeek: Int): Long {
        val token = part.trim()
        if (token.isEmpty()) return 0L
        val odd = token.contains("单")
        val even = token.contains("双") && !odd
        val cleaned = token
            .replace("每周", "")
            .replace("单周", "")
            .replace("双周", "")
            .replace("周", "")
            .replace("单", "")
            .replace("双", "")
            .replace("(", "")
            .replace(")", "")
            .replace("（", "")
            .replace("）", "")
            .trim()
        var mask = 0L
        if (cleaned.isEmpty()) {
            mask = fromRange(startWeek.coerceAtLeast(1), maxWeek)
        } else {
            val range = cleaned.split("-", "–", "—", "~")
            if (range.size >= 2) {
                val a = range[0].filter { it.isDigit() }.toIntOrNull()
                val b = range[1].filter { it.isDigit() }.toIntOrNull()
                if (a != null && b != null) mask = fromRange(min(a, b), max(a, b))
            } else {
                val n = cleaned.filter { it.isDigit() }.toIntOrNull()
                if (n != null) mask = bit(n)
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
        0xFFC62828L, 0xFFAD1457L, 0xFFC2185BL, 0xFF880E4FL, 0xFF6A1B9AL,
        0xFF4527A0L, 0xFF512DA8L, 0xFF283593L, 0xFF1A237EL, 0xFF1565C0L,
        0xFF0277BDL, 0xFF0288D1L, 0xFF00838FL, 0xFF00695CL, 0xFF004D40L,
        0xFF2E7D32L, 0xFF33691EL, 0xFF558B2FL, 0xFFEF6C00L, 0xFFE65100L,
        0xFFD84315L, 0xFFBF360CL, 0xFF4E342EL, 0xFF5D4037L, 0xFF6D4C41L,
        0xFF455A64L, 0xFF37474FL, 0xFF263238L, 0xFF00897BL, 0xFF00796BL,
        0xFF5E35B1L, 0xFF3949ABL,
        // 旧版 12 色，保留以兼容历史数据
        0xFFC62F2FL, 0xFFCE4E27L, 0xFFB87528L, 0xFF36A160L, 0xFF31A592L,
        0xFF2FA0BCL, 0xFF3168B9L, 0xFF4C3BBAL, 0xFFC23D9EL, 0xFFC4316CL,
        0xFF739442L, 0xFF477C90L,
    )

    // 精心调配的 16 色高辨识度课程色板：
    // 色相均匀拉开，中高饱和，亮度感知校准，白字强对比（WCAG AA 可读，无暗浊/发灰/浅淡卡顿）。
    private data class Swatch(val hue: Float, val s: Float, val l: Float)

    private val swatches = listOf(
        Swatch(0f, 0.65f, 0.48f),   // 宝石红 (Ruby Red)
        Swatch(15f, 0.68f, 0.46f),  // 暖珊瑚 (Warm Coral)
        Swatch(28f, 0.74f, 0.40f),  // 蜜柑橙 (Tangerine)
        Swatch(42f, 0.74f, 0.35f),  // 琥珀金 (Golden Amber)
        Swatch(122f, 0.64f, 0.33f), // 草木绿 (Leaf Green)
        Swatch(145f, 0.64f, 0.33f), // 翡翠绿 (Emerald)
        Swatch(165f, 0.62f, 0.33f), // 碧玉绿 (Forest Jade)
        Swatch(182f, 0.64f, 0.33f), // 孔雀青 (Teal Peacock)
        Swatch(198f, 0.66f, 0.40f), // 蔚天蓝 (Cerulean)
        Swatch(212f, 0.64f, 0.47f), // 海洋蓝 (Ocean Blue)
        Swatch(228f, 0.62f, 0.49f), // 钴青蓝 (Cobalt Sapphire)
        Swatch(252f, 0.58f, 0.49f), // 鸢尾紫 (Deep Iris)
        Swatch(272f, 0.56f, 0.47f), // 紫晶紫 (Royal Purple)
        Swatch(294f, 0.58f, 0.46f), // 丁香兰 (Plum Violet)
        Swatch(316f, 0.62f, 0.47f), // 洋红莓 (Vivid Magenta)
        Swatch(338f, 0.66f, 0.48f), // 覆盆子 (Berry Rose)
    )

    val palette: List<Long> get() = paletteFor(DEFAULT_THEME)

    fun paletteFor(theme: Long): List<Long> {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV((theme and 0xFFFFFFFFL).toInt(), hsv)
        // 若主题近无色彩（纯白或黑灰），保持中大红基准方位
        val shift = if (hsv[1] >= 0.12f) {
            (hsv[0] - swatches.first().hue + 360f) % 360f
        } else {
            0f
        }
        return if (kotlin.math.abs(shift) < 0.5f) {
            swatches.map { hsl(it.hue, it.s, it.l) }
        } else {
            swatches.map { swatch ->
                val h = (swatch.hue + shift + 360f) % 360f
                val (s, l) = calibratedSl(h)
                hsl(h, s, l)
            }
        }
    }

    private fun calibratedSl(h: Float): Pair<Float, Float> {
        val hue = (h % 360f + 360f) % 360f
        val s = when {
            hue in 25f..50f -> 0.74f
            hue in 115f..190f -> 0.64f
            hue in 210f..280f -> 0.60f
            else -> 0.65f
        }
        val l = when {
            hue >= 25f && hue < 45f -> {
                val t = (hue - 25f) / 20f
                0.45f * (1f - t) + 0.35f * t
            }
            hue >= 45f && hue < 70f -> 0.33f
            hue >= 70f && hue < 190f -> 0.33f
            hue >= 190f && hue < 215f -> {
                val t = (hue - 190f) / 25f
                0.33f * (1f - t) + 0.48f * t
            }
            hue >= 215f && hue < 260f -> 0.49f
            hue >= 260f && hue < 340f -> 0.47f
            else -> 0.48f
        }
        return Pair(s, l)
    }

    private fun hashSlot(name: String, destSize: Int): Int {
        var h = name.hashCode()
        h = h xor (h ushr 16)
        h = (h * 0x45d9f3b).toInt()
        h = h xor (h ushr 16)
        h = (h * 0x45d9f3b).toInt()
        h = h xor (h ushr 16)
        return (h.toUInt() % destSize.toUInt()).toInt()
    }

    fun of(name: String, theme: Long = DEFAULT_THEME): Long {
        val pal = paletteFor(theme)
        return pal[hashSlot(name, pal.size)]
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

    private fun slotOf(stored: Long, fromTheme: Long, name: String, destSize: Int): Int {
        val themed = paletteFor(fromTheme)
        themed.indexOf(stored).takeIf { it >= 0 }?.let { return it % destSize }
        legacyPalette.indexOf(stored).takeIf { it >= 0 }?.let { return it % destSize }
        return hashSlot(name, destSize)
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
