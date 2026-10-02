package com.subtitleedit.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

/** Shared motion tokens keep transitions consistent across Compose screens. */
object AppMotion {
    val Fast: FiniteAnimationSpec<Float> = tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val State: FiniteAnimationSpec<Float> = tween(250, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    val Enter: FiniteAnimationSpec<Float> = tween(300, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))
    val Exit: FiniteAnimationSpec<Float> = tween(200, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))
    val Press: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val Fade: FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
    val PressScale: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val SlideDistance: (Int) -> Int = { it }

    fun <T> fast(): FiniteAnimationSpec<T> = tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    fun <T> state(): FiniteAnimationSpec<T> = tween(250, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    fun <T> enter(): FiniteAnimationSpec<T> = tween(300, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))
    fun <T> exit(): FiniteAnimationSpec<T> = tween(200, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
    fun <T> fade(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
}
