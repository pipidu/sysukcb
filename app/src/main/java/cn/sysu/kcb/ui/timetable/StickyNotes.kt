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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
    val gridWpx = with(density) { gridW.toPx() }
    val gridHpx = with(density) { gridH.toPx() }
    var live by remember { mutableStateOf<StickyNoteEntity?>(null) }
    val shown = notes.sortedBy { it.z }.map { note ->
        if (live?.id == note.id) live!! else note
    }
    shown.forEach { note ->
        val w = gridW * note.wFrac.coerceIn(0.12f, 0.7f)
        val h = gridH * note.hFrac.coerceIn(0.08f, 0.6f)
        val x = gridW * note.xFrac.coerceIn(0f, 0.88f)
        val y = gridH * note.yFrac.coerceIn(0f, 0.9f)
        val fill = Color(note.color).copy(alpha = note.alpha.coerceIn(0.35f, 1f))
        val ink = if (fill.copy(alpha = 1f).luminance() > 0.55f) Color(0xFF3E2723) else Color.White
        Box(
            Modifier
                .offset(x, y)
                .size(w, h)
                .shadow(3.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(fill)
                .border(0.6.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .then(
                    if (editable) {
                        Modifier.pointerInput(note.id, gridWpx, gridHpx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var dragged = false
                                var current = live.takeIf { it?.id == note.id } ?: note
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    if (!dragged &&
                                        (change.position - down.position).getDistance() > touchSlop
                                    ) {
                                        dragged = true
                                    }
                                    if (dragged) {
                                        change.consume()
                                        current = current.copy(
                                            xFrac = (current.xFrac + delta.x / gridWpx)
                                                .coerceIn(0f, 1f - current.wFrac),
                                            yFrac = (current.yFrac + delta.y / gridHpx)
                                                .coerceIn(0f, 1f - current.hFrac),
                                            z = System.currentTimeMillis(),
                                        )
                                        live = current
                                    }
                                }
                                if (dragged) {
                                    onChange(current)
                                    live = null
                                } else {
                                    onEdit(note)
                                }
                            }
                        }
                    } else {
                        Modifier.clickable { onEdit(note) }
                    },
                )
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
                                var current = live.takeIf { it?.id == note.id } ?: note
                                drag(down.id) { change ->
                                    change.consume()
                                    val delta = change.positionChange()
                                    current = current.copy(
                                        wFrac = (current.wFrac + delta.x / gridWpx)
                                            .coerceIn(0.12f, 0.7f),
                                        hFrac = (current.hFrac + delta.y / gridHpx)
                                            .coerceIn(0.08f, 0.6f),
                                        z = System.currentTimeMillis(),
                                    )
                                    live = current
                                }
                                onChange(current)
                                live = null
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StickyNoteEditorDialog(
    note: StickyNoteEntity,
    themeColor: Long,
    onSave: (StickyNoteEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember(note.id) { mutableStateOf(note.content) }
    var color by remember(note.id) { mutableLongStateOf(note.color) }
    var alpha by remember(note.id) { mutableFloatStateOf(note.alpha) }
    var wFrac by remember(note.id) { mutableFloatStateOf(note.wFrac) }
    var hFrac by remember(note.id) { mutableFloatStateOf(note.hFrac) }
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
