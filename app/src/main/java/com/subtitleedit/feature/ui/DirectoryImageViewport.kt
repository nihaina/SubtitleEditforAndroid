package com.subtitleedit.feature.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.geometry.takeOrElse
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MaxPreviewZoom = 10f
private const val DoubleTapPreviewZoom = 3f

@Composable
internal fun DirectoryImageViewport(
    image: ImageBitmap,
    quarterTurns: Int,
    canPage: (Float) -> Boolean = { false },
    onTap: (() -> Unit)? = null
) {
    val previewDescription = stringResource(R.string.directory_image_preview)
    val latestCanPage by rememberUpdatedState(canPage)
    val latestOnTap by rememberUpdatedState(onTap)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width <= 0 || height <= 0) return@BoxWithConstraints
        val viewportCenter = Offset(width / 2f, height / 2f)
        val rotatedWidth = if (quarterTurns % 2 == 0) image.width else image.height
        val rotatedHeight = if (quarterTurns % 2 == 0) image.height else image.width
        val fit = minOf(width.toFloat() / rotatedWidth, height.toFloat() / rotatedHeight)
        val fittedHalfSize = Offset(rotatedWidth * fit / 2, rotatedHeight * fit / 2)
        val maxOverdrag = with(LocalDensity.current) { minOf(48.dp.toPx(), minOf(width, height) * 0.12f) }
        val scope = rememberCoroutineScope()
        val decay = rememberSplineBasedDecay<Offset>()
        val motion = remember(image, quarterTurns, viewportCenter, maxOverdrag, decay) {
            ImagePreviewMotion(viewportCenter, fittedHalfSize, maxOverdrag, scope, decay)
        }
        DisposableEffect(motion) { onDispose(motion::stop) }

        Canvas(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = previewDescription
                    role = Role.Image
                }
                .pointerInput(motion) {
                    detectTapGestures(
                        onTap = { latestOnTap?.invoke() },
                        onDoubleTap = { tap -> if (!motion.doubleTap(tap)) latestOnTap?.invoke() }
                    )
                }
                .pointerInput(motion) {
                    detectImageTransforms(
                        canPan = { pan -> motion.transform.zoom > 1f || abs(pan.y) > abs(pan.x) || !latestCanPage(pan.x) },
                        onStart = motion::stop,
                        onTransform = motion::transformBy,
                        onEnd = motion::settle
                    )
                }
        ) {
            val (zoom, offset) = motion.transform
            val origin = viewportCenter + offset
            val scale = fit * zoom
            withTransform({
                translate(origin.x, origin.y)
                rotate(quarterTurns * 90f, Offset.Zero)
                scale(scale, scale, Offset.Zero)
                translate(-image.width / 2f, -image.height / 2f)
            }) {
                drawImage(image, dstSize = IntSize(image.width, image.height), filterQuality = FilterQuality.Medium)
            }
        }
    }
}

private class ImagePreviewMotion(
    private val center: Offset,
    private val fittedHalfSize: Offset,
    private val maxOverdrag: Float,
    private val scope: CoroutineScope,
    private val decay: DecayAnimationSpec<Offset>
) {
    var transform by mutableStateOf(ImagePreviewTransform())
        private set
    private var animation: Job? = null

    fun stop() {
        animation?.cancel()
    }

    fun transformBy(centroid: Offset, zoom: Float, pan: Offset) {
        val current = transform
        val targetZoom = (current.zoom * zoom).coerceIn(1f, MaxPreviewZoom)
        val ratio = targetZoom / current.zoom
        val limits = panLimits(fittedHalfSize, center, targetZoom)
        val offset = if (zoom == 1f) {
            Offset(
                elasticImagePan(current.offset.x, pan.x, limits.x, maxOverdrag),
                elasticImagePan(current.offset.y, pan.y, limits.y, maxOverdrag)
            )
        } else {
            val anchor = centroid.takeOrElse { center }
            (current.offset * ratio + pan + (anchor - center) * (1f - ratio)).clampTo(limits)
        }
        transform = ImagePreviewTransform(targetZoom, offset)
    }

    fun settle(velocity: Velocity) {
        val current = transform
        val limits = panLimits(fittedHalfSize, center, current.zoom)
        val target = current.offset.clampTo(limits)
        if (target != current.offset) {
            stop()
            animation = scope.launch {
                Animatable(current.offset, Offset.VectorConverter).animateTo(
                    target, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                ) { transform = current.copy(offset = value) }
            }
        } else if (current.zoom > 1f && velocity != Velocity.Zero && limits != Offset.Zero) {
            stop()
            animation = scope.launch {
                Animatable(current.offset, Offset.VectorConverter).animateDecay(Offset(velocity.x, velocity.y), decay) {
                    transform = current.copy(offset = value.clampTo(limits))
                }
            }
        }
    }

    fun doubleTap(tap: Offset): Boolean {
        val current = transform
        val relativeTap = tap - center - current.offset
        val imageHalfSize = fittedHalfSize * current.zoom
        if (abs(relativeTap.x) > imageHalfSize.x || abs(relativeTap.y) > imageHalfSize.y) return false

        val target = doubleTapImageTransform(current, relativeTap, fittedHalfSize, center)
        stop()
        animation = scope.launch {
            animate(0f, 1f, animationSpec = tween(220)) { fraction, _ ->
                val zoom = current.zoom + (target.zoom - current.zoom) * fraction
                val offset = lerp(current.offset, target.offset, fraction).clampTo(panLimits(fittedHalfSize, center, zoom))
                transform = ImagePreviewTransform(zoom, offset)
            }
            transform = target
        }
        return true
    }
}

internal fun doubleTapImageTransform(
    current: ImagePreviewTransform,
    relativeTap: Offset,
    fittedHalfSize: Offset,
    center: Offset
): ImagePreviewTransform {
    if (current.zoom >= DoubleTapPreviewZoom) return ImagePreviewTransform()
    val target = relativeTap * (-DoubleTapPreviewZoom / current.zoom)
    return ImagePreviewTransform(DoubleTapPreviewZoom, target.clampTo(panLimits(fittedHalfSize, center, DoubleTapPreviewZoom)))
}

private fun panLimits(fittedHalfSize: Offset, viewportCenter: Offset, zoom: Float): Offset {
    val overflow = fittedHalfSize * zoom - viewportCenter
    return Offset(overflow.x.coerceAtLeast(0f), overflow.y.coerceAtLeast(0f))
}

private fun Offset.clampTo(limits: Offset): Offset = Offset(
    if (limits.x == 0f) 0f else x.coerceIn(-limits.x, limits.x),
    if (limits.y == 0f) 0f else y.coerceIn(-limits.y, limits.y)
)

internal fun elasticImagePan(offset: Float, delta: Float, limit: Float, maxOverdrag: Float): Float {
    val requested = offset + delta
    val edge = requested.coerceIn(-limit, limit)
    val overshoot = requested - edge
    val existingOvershoot = offset - offset.coerceIn(-limit, limit)
    val outwardTravel = when {
        overshoot > 0f -> (overshoot - existingOvershoot.coerceAtLeast(0f)).coerceAtLeast(0f)
        overshoot < 0f -> (overshoot - existingOvershoot.coerceAtMost(0f)).coerceAtMost(0f)
        else -> 0f
    }
    return edge + (overshoot - outwardTravel * 0.7f).coerceIn(-maxOverdrag, maxOverdrag)
}

internal data class ImagePreviewTransform(val zoom: Float = 1f, val offset: Offset = Offset.Zero)
