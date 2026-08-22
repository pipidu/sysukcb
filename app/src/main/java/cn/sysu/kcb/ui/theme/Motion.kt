package cn.sysu.kcb.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

object KcbMotion {
    const val fast = 180
    const val normal = 280

    fun fadeSlideIn(forward: Boolean = true): EnterTransition {
        val dir = if (forward) 1 else -1
        return fadeIn(tween(normal, easing = FastOutSlowInEasing)) +
            slideInHorizontally(tween(normal, easing = FastOutSlowInEasing)) { dir * it / 6 }
    }

    fun fadeSlideOut(forward: Boolean = false): ExitTransition {
        val dir = if (forward) 1 else -1
        return fadeOut(tween(fast)) +
            slideOutHorizontally(tween(fast)) { dir * it / 8 }
    }

    val fadeIn = fadeIn(tween(fast))
    val fadeOut = fadeOut(tween(160))

    val overlayEnter = fadeIn(tween(200)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220))
    val overlayExit = fadeOut(tween(240)) + scaleOut(targetScale = 0.98f, animationSpec = tween(200))

    fun weekPage(forward: Boolean): ContentTransform {
        val dir = if (forward) 1 else -1
        return (fadeIn(tween(220)) + slideInHorizontally(tween(normal, easing = FastOutSlowInEasing)) { dir * it / 5 }) togetherWith
            (fadeOut(tween(160)) + slideOutHorizontally(tween(240)) { -dir * it / 5 })
    }
}
