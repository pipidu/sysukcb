package cn.sysu.kcb.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sysu.kcb.data.local.StickyNoteEntity
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.ui.theme.NamedStickyNoteColors
import kotlin.math.abs
import kotlin.math.roundToInt

internal val StickyNotePaper = NamedStickyNoteColors.map { it.first }

private val noteBody = TextStyle(
    fontSize = 11.sp,
    lineHeight = 13.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

private fun noteTextStyle(note: StickyNoteEntity): TextStyle {
    val size = note.fontSizeSp.coerceIn(MIN_NOTE_FONT_SP, MAX_NOTE_FONT_SP)
    return noteBody.copy(
        fontSize = size.sp,
        lineHeight = (size + 2).sp,
        fontWeight = if (note.fontHighlight) FontWeight.SemiBold else FontWeight.Normal,
        background = if (note.fontHighlight) noteHighlightColor(note.color) else Color.Unspecified,
    )
}

private fun noteHighlightColor(paper: Long): Color {
    val fill = Color(paper)
    return if (fill.luminance() > 0.82f) {
        Color(0xFFFFD54F).copy(alpha = 0.72f)
    } else {
        Color(0xFFFFEA00).copy(alpha = 0.55f)
    }
}

private const val MIN_NOTE_FONT_SP = 9
private const val MAX_NOTE_FONT_SP = 22

internal fun defaultStickyNote(semester: String, existingCount: Int, week: Int = 1): StickyNoteEntity {
    val slot = existingCount % 5
    val weekNo = week.coerceIn(1, WeekMask.MAX_WEEK)
    return StickyNoteEntity(
        acadYearSemester = semester,
        content = "",
        xFrac = (0.16f + slot * 0.05f).coerceAtMost(0.62f),
        yFrac = (0.12f + slot * 0.06f).coerceAtMost(0.62f),
        wFrac = 0.28f,
        hFrac = 0.16f,
        color = StickyNotePaper[slot % StickyNotePaper.size],
        alpha = 0.92f,
        z = System.currentTimeMillis(),
        weeksMask = WeekMask.bit(weekNo),
    )
}

internal fun stickyNotesOnWeek(notes: List<StickyNoteEntity>, weekNo: Int): List<StickyNoteEntity> =
    notes.filter { WeekMask.showsOn(it.weeksMask, weekNo) }

private data class NoteLayout(
    val xFrac: Float,
    val yFrac: Float,
    val wFrac: Float,
    val hFrac: Float,
)

@Composable
internal fun StickyNoteLayer(
    notes: List<StickyNoteEntity>,
    gridW: Dp,
    gridH: Dp,
    editable: Boolean,
    onChange: (StickyNoteEntity) -> Unit,
    onEdit: (StickyNoteEntity) -> Unit,
) {
    if (notes.isEmpty()) return
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val gridWpx = with(density) { gridW.toPx() }.coerceAtLeast(1f)
    val gridHpx = with(density) { gridH.toPx() }.coerceAtLeast(1f)
    val notesLatest = rememberUpdatedState(notes)
    val onChangeLatest = rememberUpdatedState(onChange)
    val onEditLatest = rememberUpdatedState(onEdit)
    var overlay by remember { mutableStateOf<Map<Long, NoteLayout>>(emptyMap()) }
    val overlayLatest = rememberUpdatedState(overlay)
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragPx by remember { mutableStateOf(Offset.Zero) }
    var resizeId by remember { mutableStateOf<Long?>(null) }
    var resizePx by remember { mutableStateOf(Offset.Zero) }
    fun laidOut(note: StickyNoteEntity): NoteLayout =
        overlay[note.id] ?: NoteLayout(note.xFrac, note.yFrac, note.wFrac, note.hFrac)
    fun latest(id: Long): StickyNoteEntity? {
        val note = notesLatest.value.firstOrNull { it.id == id } ?: return null
        val layout = overlayLatest.value[id] ?: return note
        return note.copy(xFrac = layout.xFrac, yFrac = layout.yFrac, wFrac = layout.wFrac, hFrac = layout.hFrac)
    }
    LaunchedEffect(notes) {
        if (overlay.isEmpty()) return@LaunchedEffect
        overlay = overlay.filter { (id, layout) ->
            val note = notes.firstOrNull { it.id == id } ?: return@filter false
            abs(note.xFrac - layout.xFrac) > 0.0001f ||
                abs(note.yFrac - layout.yFrac) > 0.0001f ||
                abs(note.wFrac - layout.wFrac) > 0.0001f ||
                abs(note.hFrac - layout.hFrac) > 0.0001f
        }
    }

    notes.sortedBy { it.z }.forEach { note ->
        val dragging = dragId == note.id
        val resizing = resizeId == note.id
        val layout = laidOut(note)
        val w = gridW * layout.wFrac.coerceIn(0.12f, 0.7f)
        val h = gridH * layout.hFrac.coerceIn(0.08f, 0.6f)
        val x = gridW * layout.xFrac.coerceIn(0f, 0.88f)
        val y = gridH * layout.yFrac.coerceIn(0f, 0.9f)
        val baseWpx = with(density) { w.toPx() }.coerceAtLeast(1f)
        val baseHpx = with(density) { h.toPx() }.coerceAtLeast(1f)
        val fill = Color(note.color).copy(alpha = note.alpha.coerceIn(0.35f, 1f))
        val ink = if (fill.copy(alpha = 1f).luminance() > 0.55f) Color(0xFF3E2723) else Color.White
        Box(
            Modifier
                .offset(x, y)
                .size(w, h)
                .then(
                    if (editable) {
                        Modifier.pointerInput(note.id, gridWpx, gridHpx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var moved = Offset.Zero
                                var dragged = false
                                drag(down.id) { change ->
                                    moved += change.positionChange()
                                    if (!dragged && moved.getDistance() > touchSlop) dragged = true
                                    if (dragged) {
                                        change.consume()
                                        dragId = note.id
                                        dragPx = moved
                                    }
                                }
                                if (dragged) {
                                    latest(note.id)?.let { current ->
                                        val next = NoteLayout(
                                            xFrac = (current.xFrac + moved.x / gridWpx)
                                                .coerceIn(0f, 1f - current.wFrac),
                                            yFrac = (current.yFrac + moved.y / gridHpx)
                                                .coerceIn(0f, 1f - current.hFrac),
                                            wFrac = current.wFrac,
                                            hFrac = current.hFrac,
                                        )
                                        overlay = overlayLatest.value + (note.id to next)
                                        onChangeLatest.value(
                                            current.copy(
                                                xFrac = next.xFrac,
                                                yFrac = next.yFrac,
                                                z = System.currentTimeMillis(),
                                            ),
                                        )
                                    }
                                    dragId = null
                                    dragPx = Offset.Zero
                                } else {
                                    latest(note.id)?.let { onEditLatest.value(it) }
                                }
                            }
                        }
                    } else {
                        Modifier.clickable { onEdit(note) }
                    },
                ),
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = if (dragging) dragPx.x else 0f
                        translationY = if (dragging) dragPx.y else 0f
                        transformOrigin = TransformOrigin(0f, 0f)
                        if (resizing) {
                            scaleX = ((baseWpx + resizePx.x) / baseWpx).coerceIn(0.35f, 3.5f)
                            scaleY = ((baseHpx + resizePx.y) / baseHpx).coerceIn(0.35f, 3.5f)
                        }
                    }
                    .fillMaxSize()
                    .shadow(3.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(fill)
                    .border(0.6.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                    .padding(6.dp),
            ) {
                Text(
                    note.content.ifBlank { "便签" },
                    color = ink.copy(alpha = if (note.content.isBlank()) 0.55f else 1f),
                    style = noteTextStyle(note),
                    overflow = TextOverflow.Ellipsis,
                )
                if (editable) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(14.dp)
                            .pointerInput(note.id, gridWpx, gridHpx) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var moved = Offset.Zero
                                    resizeId = note.id
                                    drag(down.id) { change ->
                                        change.consume()
                                        moved += change.positionChange()
                                        resizePx = moved
                                    }
                                    latest(note.id)?.let { current ->
                                        val next = NoteLayout(
                                            xFrac = current.xFrac,
                                            yFrac = current.yFrac,
                                            wFrac = (current.wFrac + moved.x / gridWpx)
                                                .coerceIn(0.12f, 0.7f),
                                            hFrac = (current.hFrac + moved.y / gridHpx)
                                                .coerceIn(0.08f, 0.6f),
                                        )
                                        overlay = overlayLatest.value + (note.id to next)
                                        onChangeLatest.value(
                                            current.copy(
                                                wFrac = next.wFrac,
                                                hFrac = next.hFrac,
                                                z = System.currentTimeMillis(),
                                            ),
                                        )
                                    }
                                    resizeId = null
                                    resizePx = Offset.Zero
                                }
                            },
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(8.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(ink.copy(alpha = 0.35f)),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StickyNoteEditorDialog(
    note: StickyNoteEntity,
    themeColor: Long,
    maxWeek: Int,
    currentWeek: Int,
    onSave: (StickyNoteEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val weekCount = ((maxWeek.coerceAtLeast(1) + 4) / 5 * 5).coerceIn(5, WeekMask.MAX_WEEK)
    val initialMask = if (note.weeksMask == 0L) WeekMask.fromRange(1, weekCount) else note.weeksMask
    var content by remember(note.id, note.content, note.color, note.alpha, note.wFrac, note.hFrac, note.weeksMask, note.fontSizeSp, note.fontHighlight) {
        mutableStateOf(note.content)
    }
    var color by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac, note.weeksMask, note.fontSizeSp, note.fontHighlight) { mutableLongStateOf(note.color) }
    var alpha by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac, note.weeksMask, note.fontSizeSp, note.fontHighlight) { mutableFloatStateOf(note.alpha) }
    var wFrac by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac, note.weeksMask, note.fontSizeSp, note.fontHighlight) { mutableFloatStateOf(note.wFrac) }
    var hFrac by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac, note.weeksMask, note.fontSizeSp, note.fontHighlight) { mutableFloatStateOf(note.hFrac) }
    var weeksMask by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac, note.weeksMask, note.fontSizeSp, note.fontHighlight) {
        mutableLongStateOf(initialMask)
    }
    var fontSizeSp by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac, note.weeksMask, note.fontSizeSp, note.fontHighlight) {
        mutableIntStateOf(note.fontSizeSp.coerceIn(MIN_NOTE_FONT_SP, MAX_NOTE_FONT_SP))
    }
    var fontHighlight by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac, note.weeksMask, note.fontSizeSp, note.fontHighlight) {
        mutableStateOf(note.fontHighlight)
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val palette = remember(themeColor) { StickyNotePaper + CourseColors.paletteFor(themeColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("便签") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(400) },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    minLines = 4,
                )
                Text("显示周次（${WeekMask.describe(weeksMask, weekCount).ifBlank { "未选" }}）", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = weeksMask == WeekMask.bit(currentWeek.coerceIn(1, weekCount)),
                        onClick = { weeksMask = WeekMask.bit(currentWeek.coerceIn(1, weekCount)) },
                        label = { Text("本周") },
                    )
                    FilterChip(
                        selected = weeksMask == WeekMask.fromRange(1, weekCount),
                        onClick = { weeksMask = WeekMask.fromRange(1, weekCount) },
                        label = { Text("全选") },
                    )
                    FilterChip(
                        selected = weeksMask == WeekMask.fromRange(1, weekCount) { it % 2 == 1 },
                        onClick = { weeksMask = WeekMask.fromRange(1, weekCount) { it % 2 == 1 } },
                        label = { Text("单周") },
                    )
                    FilterChip(
                        selected = weeksMask == WeekMask.fromRange(1, weekCount) { it % 2 == 0 },
                        onClick = { weeksMask = WeekMask.fromRange(1, weekCount) { it % 2 == 0 } },
                        label = { Text("双周") },
                    )
                    FilterChip(
                        selected = weeksMask == 0L,
                        onClick = { weeksMask = 0L },
                        label = { Text("清空") },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(weekCount / 5) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(5) { col ->
                                val w = row * 5 + col + 1
                                val on = WeekMask.has(weeksMask, w)
                                val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bg)
                                        .clickable {
                                            weeksMask = if (on) {
                                                weeksMask and WeekMask.bit(w).inv()
                                            } else {
                                                weeksMask or WeekMask.bit(w)
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("$w", color = fg, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Text("颜色", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { swatch ->
                        val selected = color == swatch
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .border(
                                    width = if (selected) 2.dp else 0.8.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.15f),
                                    shape = CircleShape,
                                )
                                .clickable { color = swatch },
                        )
                    }
                }
                Text("透明度 ${(alpha * 100).roundToInt()}%")
                Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.35f..1f)
                Text("宽度 ${(wFrac * 100).roundToInt()}%")
                Slider(value = wFrac, onValueChange = { wFrac = it }, valueRange = 0.12f..0.7f)
                Text("高度 ${(hFrac * 100).roundToInt()}%")
                Slider(value = hFrac, onValueChange = { hFrac = it }, valueRange = 0.08f..0.6f)
                Text("字号 ${fontSizeSp} sp")
                Slider(
                    value = fontSizeSp.toFloat(),
                    onValueChange = { fontSizeSp = it.toInt().coerceIn(MIN_NOTE_FONT_SP, MAX_NOTE_FONT_SP) },
                    valueRange = MIN_NOTE_FONT_SP.toFloat()..MAX_NOTE_FONT_SP.toFloat(),
                    steps = MAX_NOTE_FONT_SP - MIN_NOTE_FONT_SP - 1,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("文字高亮", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Switch(checked = fontHighlight, onCheckedChange = { fontHighlight = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        note.copy(
                            content = content.trim(),
                            color = color,
                            alpha = alpha,
                            wFrac = wFrac,
                            hFrac = hFrac,
                            weeksMask = weeksMask,
                            fontSizeSp = fontSizeSp,
                            fontHighlight = fontHighlight,
                            z = System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这张便签？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun StickyNoteViewDialog(note: StickyNoteEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("便签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    note.content.ifBlank { "（空白便签）" },
                    style = noteTextStyle(note),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (note.weeksMask != 0L) {
                    Text(
                        WeekMask.describe(note.weeksMask),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
