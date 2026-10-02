package com.subtitleedit.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** Shared motion tokens keep transitions consistent across Compose screens. */
object AppMotion {
    const val DurationShort = 150
    const val DurationMedium = 250
    const val DurationLong = 350

    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> fast(): FiniteAnimationSpec<T> = tween(DurationShort, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    fun <T> state(): FiniteAnimationSpec<T> = tween(DurationMedium, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    fun <T> enter(): FiniteAnimationSpec<T> = tween(DurationLong, easing = EmphasizedDecelerate)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(200, easing = EmphasizedAccelerate)
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
    fun <T> fade(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
}
