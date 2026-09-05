package cn.sysu.kcb.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val KcbTopBarHeight = 48.dp
val KcbBottomBarHeight = 56.dp

val LocalKcbTopBarHeight = compositionLocalOf { KcbTopBarHeight }
val LocalKcbBottomBarHeight = compositionLocalOf { KcbBottomBarHeight }

@Composable
fun KcbTopBar(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    height: Dp = LocalKcbTopBarHeight.current,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(color = containerColor, contentColor = contentColor, modifier = modifier) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(height),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
