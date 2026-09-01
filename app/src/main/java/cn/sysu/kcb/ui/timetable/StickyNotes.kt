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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlin.math.roundToInt

internal val StickyNotePaper = listOf(
    0xFFFFF59DL,
    0xFFFFCCBCL,
    0xFFC8E6C9L,
    0xFFBBDEFBL,
    0xFFF8BBD0L,
    0xFFE1BEE7L,
    0xFFFFE0B2L,
    0xFFFFFFFFL,
)

private val noteBody = TextStyle(
    fontSize = 11.sp,
    lineHeight = 13.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

internal fun defaultStickyNote(semester: String, existingCount: Int): StickyNoteEntity {
    val slot = existingCount % 5
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
    )
}

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
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragPx by remember { mutableStateOf(Offset.Zero) }
    var resizeId by remember { mutableStateOf<Long?>(null) }
    var resizePx by remember { mutableStateOf(Offset.Zero) }
    fun latest(id: Long) = notesLatest.value.firstOrNull { it.id == id }

    notes.sortedBy { it.z }.forEach { note ->
        val dragging = dragId == note.id
        val resizing = resizeId == note.id
        val w = gridW * note.wFrac.coerceIn(0.12f, 0.7f)
        val h = gridH * note.hFrac.coerceIn(0.08f, 0.6f)
        val x = gridW * note.xFrac.coerceIn(0f, 0.88f)
        val y = gridH * note.yFrac.coerceIn(0f, 0.9f)
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
                                        onChangeLatest.value(
                                            current.copy(
                                                xFrac = (current.xFrac + moved.x / gridWpx)
                                                    .coerceIn(0f, 1f - current.wFrac),
                                                yFrac = (current.yFrac + moved.y / gridHpx)
                                                    .coerceIn(0f, 1f - current.hFrac),
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
                    style = noteBody,
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
                                        onChangeLatest.value(
                                            current.copy(
                                                wFrac = (current.wFrac + moved.x / gridWpx)
                                                    .coerceIn(0.12f, 0.7f),
                                                hFrac = (current.hFrac + moved.y / gridHpx)
                                                    .coerceIn(0.08f, 0.6f),
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
    onSave: (StickyNoteEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember(note.id, note.content, note.color, note.alpha, note.wFrac, note.hFrac) {
        mutableStateOf(note.content)
    }
    var color by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac) { mutableLongStateOf(note.color) }
    var alpha by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac) { mutableFloatStateOf(note.alpha) }
    var wFrac by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac) { mutableFloatStateOf(note.wFrac) }
    var hFrac by remember(note.id, note.color, note.alpha, note.wFrac, note.hFrac) { mutableFloatStateOf(note.hFrac) }
    var confirmDelete by remember { mutableStateOf(false) }
    val palette = remember(themeColor) { StickyNotePaper + CourseColors.paletteFor(themeColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("便签") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
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
        text = { Text(note.content.ifBlank { "（空白便签）" }) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
