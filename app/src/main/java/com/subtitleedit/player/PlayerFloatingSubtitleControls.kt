package com.subtitleedit.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.subtitleedit.R
import kotlin.math.roundToInt

/** Controls shown below the subtitle when the unlocked overlay is tapped. */
internal class PlayerFloatingSubtitleControls(
    context: Context,
    onAction: (PlayerFloatingSubtitleAction) -> Unit,
    onFontSizeDelta: (Int) -> Unit,
) : LinearLayout(context) {
    private val title = TextView(context).apply {
        setTextColor(CONTROL_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), 0)
        tooltipText = context.getString(R.string.player_floating_subtitle_fold)
        setOnClickListener { onAction(PlayerFloatingSubtitleAction.TOGGLE_CONTROLS) }
    }
    private val lock = iconButton(R.drawable.ic_player_locked, R.string.player_floating_subtitle_lock) {
        onAction(PlayerFloatingSubtitleAction.LOCK)
    }
    private val previous = iconButton(R.drawable.ic_skip_previous, R.string.player_previous) {
        onAction(PlayerFloatingSubtitleAction.PREVIOUS)
    }
    private val playPause = iconButton(R.drawable.ic_video_play, R.string.player_play) {
        onAction(PlayerFloatingSubtitleAction.PLAY_PAUSE)
    }
    private val next = iconButton(R.drawable.ic_skip_next, R.string.player_next) {
        onAction(PlayerFloatingSubtitleAction.NEXT)
    }
    private val close = iconButton(R.drawable.ic_close, R.string.player_floating_subtitle_close) {
        onAction(PlayerFloatingSubtitleAction.CLOSE)
    }

    private val palette = iconButton(R.drawable.ic_palette, R.string.player_floating_subtitle_font_color) {
        onAction(PlayerFloatingSubtitleAction.PICK_COLOR)
    }
    private val backgroundColor = iconButton(
        R.drawable.ic_subtitle_background, R.string.player_floating_subtitle_background_color,
    ) {
        onAction(PlayerFloatingSubtitleAction.PICK_BACKGROUND_COLOR)
    }
    private val smaller = button(R.string.player_floating_subtitle_smaller) { onFontSizeDelta(-FONT_SIZE_STEP) }.apply {
        setImageDrawable(GradientDrawable().apply {
            setColor(CONTROL_COLOR)
            setSize(dp(14), dp(2))
            cornerRadius = dp(1).toFloat()
        })
    }
    private val larger = iconButton(R.drawable.ic_add, R.string.player_floating_subtitle_larger) {
        onFontSizeDelta(FONT_SIZE_STEP)
    }

    private var renderedPlaying: Boolean? = null

    init {
        orientation = VERTICAL
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            background = cardBackground()
            addView(title, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(buttonRow().apply {
                listOf(lock, previous, playPause, next, close).forEach { addButton(it) }
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(buttonRow().apply {
            background = cardBackground()
            addButton(palette)
            addButton(backgroundColor)
            addView(View(context), LayoutParams(0, 0, 1f))
            addButton(smaller)
            addButton(larger)
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    fun render(playback: PlayerFloatingSubtitlePlayback?, style: PlayerFloatingSubtitleStyle) {
        val trackTitle = playback?.title.orEmpty()
        if (title.text != trackTitle) title.text = trackTitle
        title.visibility = if (trackTitle.isBlank()) GONE else VISIBLE
        previous.showEnabled(playback?.hasPrevious == true)
        playPause.showEnabled(playback != null)
        next.showEnabled(playback?.hasNext == true)
        if (renderedPlaying != playback?.playing) {
            renderedPlaying = playback?.playing
            val playing = renderedPlaying == true
            playPause.setImageResource(if (playing) R.drawable.ic_video_pause else R.drawable.ic_video_play)
            playPause.contentDescription = context.getString(if (playing) R.string.player_pause else R.string.player_play)
        }

        smaller.showEnabled(style.fontSize > PlayerPreferences.MIN_SUBTITLE_FONT_SIZE)
        larger.showEnabled(style.fontSize < PlayerPreferences.MAX_SUBTITLE_FONT_SIZE)
    }

    private fun buttonRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun LinearLayout.addButton(button: ImageButton) {
        addView(button, LayoutParams(0, dp(48), 1f))
    }

    private fun iconButton(
        @DrawableRes icon: Int,
        @StringRes label: Int,
        onClick: () -> Unit,
    ): ImageButton = button(label, onClick).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(CONTROL_COLOR)
    }

    private fun button(@StringRes label: Int, onClick: () -> Unit): ImageButton = ImageButton(context).apply {
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = RippleDrawable(ColorStateList.valueOf(0x18000000), null, GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        })
        contentDescription = context.getString(label)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun ImageButton.showEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.35f
    }

    private fun cardBackground() = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(0xE8FFFFFF.toInt())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        val CONTROL_COLOR = 0xFF202124.toInt()
        const val FONT_SIZE_STEP = 2
    }
}
