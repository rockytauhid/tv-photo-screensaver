// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Gold is reserved for state: a favorite, an enabled option, the current year or month. */
internal const val GOLD_COLOR = 0xFFFFC107.toInt()

/** Uniform TV vertical safe-area margin (bottom) used across gallery and preview. */
internal const val TV_EDGE_MARGIN_DP = 20

/** Top margin for the 44dp header row so the visual text/icon top aligns with the 30dp left edge. */
internal const val TV_HEADER_TOP_MARGIN_DP = 14

/** Uniform TV horizontal safe-area margin (left & right), aligned with the gallery Year text. */
internal const val TV_HORIZONTAL_MARGIN_DP = 30

private const val DISABLED_COLOR = 0xFF666666.toInt()
private const val CARD_FILL_COLOR = 0xFF1E1E1E.toInt()
private const val CARD_FOCUSED_FILL_COLOR = 0xFF333333.toInt()
private const val CARD_DISABLED_FILL_COLOR = 0xFF161616.toInt()
private const val CARD_SUBTLE_BORDER_COLOR = 0xFF303030.toInt()
private const val CARD_TEXT_COLOR = 0xFFEBEBEB.toInt()

/** Semi-transparent panel that slides in from the right over a dimmed screen. */
internal class RightOptionsOverlay(context: Context) {
    val view = FrameLayout(context).apply {
        setBackgroundColor(0xB8000000.toInt())
        visibility = View.GONE
        setOnClickListener { hide() }
    }

    private val panelWidth =
        (context.resources.displayMetrics.widthPixels / 4).coerceAtLeast(1)
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(24), context.dp(24), context.dp(24), 0)
        setBackgroundColor(0xD9222222.toInt())
        setOnClickListener { }
    }

    val isVisible: Boolean
        get() = view.visibility == View.VISIBLE

    var onDismissed: (() -> Unit)? = null

    init {
        panel.addView(TextView(context).apply {
            text = context.getString(R.string.options_title)
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, context.dp(12))
        }, LinearLayout.LayoutParams(-1, -2))
        view.addView(
            panel,
            FrameLayout.LayoutParams(panelWidth, -1, Gravity.RIGHT)
        )
    }

    fun addAction(label: CharSequence, onClick: () -> Unit): TextView {
        val action = view.context.createOptionText(label, 15f, 52, onClick)
        panel.addView(
            action,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = view.context.dp(8) }
        )
        return action
    }

    fun addBottomAction(label: CharSequence, onClick: () -> Unit): TextView {
        panel.addView(View(view.context), LinearLayout.LayoutParams(-1, 0, 1f))
        val action = view.context.createOptionText(label, 13f, 36, onClick)
        panel.addView(action, LinearLayout.LayoutParams(-1, view.context.dp(36)))
        return action
    }

    fun show(initialFocus: View? = null) {
        if (isVisible) return
        view.visibility = View.VISIBLE
        panel.translationX = panelWidth.toFloat()
        panel.animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
        initialFocus?.requestFocus()
    }

    fun hide(onHidden: () -> Unit = {}) {
        if (!isVisible) return
        panel.animate().cancel()
        panel.animate()
            .translationX(panelWidth.toFloat())
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                panel.translationX = 0f
                onHidden()
                onDismissed?.invoke()
            }
            .start()
    }
}

/** Text-only option: no background, shadow or border. A ring is drawn only while it is highlighted. */
internal fun Context.createOptionText(
    label: CharSequence,
    textSizeSp: Float = 15f,
    minHeightDp: Int = 52,
    onClick: () -> Unit
): TextView {
    val ctx = this
    return TextView(ctx).apply {
        text = label
        textSize = textSizeSp
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(DISABLED_COLOR, Color.WHITE)
            )
        )
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
        minHeight = ctx.dp(minHeightDp)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = null
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) ctx.focusRing(circle = false) else null
            (v as TextView).setTypeface(null, if (hasFocus) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}

/**
 * Rounded dark card option button with unmistakable D-pad focus (elevated fill + 2dp white border),
 * visually distinct from plain helper/section text.
 */
internal fun Context.createCardOption(
    label: CharSequence = "",
    textSizeSp: Float = 16f,
    minHeightDp: Int = 48,
    pill: Boolean = false,
    onClick: () -> Unit
): TextView {
    val ctx = this
    return TextView(ctx).apply {
        text = label
        textSize = textSizeSp
        gravity = if (pill) Gravity.CENTER else Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(18), ctx.dp(10), ctx.dp(18), ctx.dp(10))
        minHeight = ctx.dp(minHeightDp)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        styleCardOption(ctx, this, hasFocus = false, pill = pill)
        setOnClickListener { if (isEnabled) onClick() }
        setOnFocusChangeListener { v, hasFocus ->
            styleCardOption(ctx, v as TextView, hasFocus, pill)
        }
    }
}

internal fun Context.refreshCardOptionStyle(button: TextView, pill: Boolean = false) {
    button.isFocusable = button.isEnabled
    button.isFocusableInTouchMode = button.isEnabled
    styleCardOption(this, button, button.hasFocus(), pill)
}

private fun styleCardOption(
    context: Context,
    button: TextView,
    hasFocus: Boolean,
    pill: Boolean
) {
    val enabled = button.isEnabled
    button.setTextColor(
        when {
            !enabled -> DISABLED_COLOR
            hasFocus -> Color.WHITE
            else -> CARD_TEXT_COLOR
        }
    )
    button.setTypeface(null, if (hasFocus && enabled) Typeface.BOLD else Typeface.NORMAL)
    button.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(if (pill) 100 else 10).toFloat()
        setColor(
            when {
                !enabled -> CARD_DISABLED_FILL_COLOR
                hasFocus -> CARD_FOCUSED_FILL_COLOR
                else -> CARD_FILL_COLOR
            }
        )
        when {
            hasFocus && enabled -> setStroke(context.dp(2), Color.WHITE)
            enabled -> setStroke(context.dp(1), CARD_SUBTLE_BORDER_COLOR)
            else -> setStroke(0, Color.TRANSPARENT)
        }
    }
}

/** Shows "Label: On" or "Label: Off" with the On in gold. */
internal fun TextView.setToggleText(label: String, enabled: Boolean) {
    val value = context.getString(if (enabled) R.string.on else R.string.off)
    val full = "$label: $value"
    text = if (enabled) {
        SpannableString(full).apply {
            setSpan(
                ForegroundColorSpan(GOLD_COLOR),
                label.length + 2,
                full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    } else {
        full
    }
}

/** Icon button: white, circled while highlighted, gold while active (see [setIconActive]). */
internal fun Context.createIconButton(
    iconResource: Int,
    description: String,
    onClick: () -> Unit
): ImageButton {
    val ctx = this
    return ImageButton(ctx).apply {
        setImageResource(iconResource)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        background = null
        minimumWidth = ctx.dp(44)
        minimumHeight = ctx.dp(44)
        isFocusable = true
        isFocusableInTouchMode = true
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) ctx.focusRing(circle = true) else null
        }
        setOnClickListener { onClick() }
    }
}

internal fun ImageButton.setIconActive(active: Boolean) {
    imageTintList = ColorStateList.valueOf(if (active) GOLD_COLOR else Color.WHITE)
}

internal fun Context.focusRing(circle: Boolean): GradientDrawable =
    GradientDrawable().apply {
        shape = if (circle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        // Larger than half the height: the drawable clamps it, which gives a pill shape.
        if (!circle) cornerRadius = dp(100).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), Color.WHITE)
    }

internal fun Activity.openScreensaverSettings() {
    try {
        startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.screensaver_settings_unavailable, Toast.LENGTH_LONG).show()
    }
}

/**
 * Shared visual stage for both the system screensaver ([PhotoDream]) and manual slideshow
 * ([SlideshowActivity]) so both modes stay visually identical.
 */
internal class SlideshowStage(context: Context, handler: Handler) {
    val imageViews: Array<ImageView> = Array(2) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
            setBackgroundColor(Color.BLACK)
        }
    }
    val dateLabel: TextView = footerLabel(context, Gravity.START)
    val locationLabel: TextView = footerLabel(context, Gravity.END)
    val status: TextView = TextView(context).apply {
        setTextColor(0xFFBBBBBB.toInt())
        textSize = 22f
        gravity = Gravity.CENTER
    }
    val clock: PhotoClock = PhotoClock(context, handler, Gravity.END)
    val root: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(imageViews[0], FrameLayout.LayoutParams(-1, -1))
        addView(imageViews[1], FrameLayout.LayoutParams(-1, -1))
        addView(status, FrameLayout.LayoutParams(-1, -1))
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dateLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(locationLabel, LinearLayout.LayoutParams(0, -2, 1f))
        }
        addView(
            footer,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(context.dp(40), 0, context.dp(40), context.dp(32))
            }
        )
        addView(
            clock.view,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
                setMargins(0, context.dp(24), context.dp(32), 0)
            }
        )
    }

    var visibleImage = 0
        private set

    fun present(
        bitmap: Bitmap,
        contentDescription: String?,
        transition: String?,
        slideDistance: Float
    ) {
        val newIndex = if (imageViews[visibleImage].drawable == null) visibleImage else 1 - visibleImage
        val oldIndex = 1 - newIndex
        val incoming = imageViews[newIndex]
        val outgoing = imageViews[oldIndex]
        incoming.animate().cancel()
        outgoing.animate().cancel()
        val replacedBitmap = (incoming.drawable as? BitmapDrawable)?.bitmap
        incoming.setImageBitmap(bitmap)
        incoming.contentDescription = contentDescription
        if (replacedBitmap != null && replacedBitmap !== bitmap) replacedBitmap.recycle()
        incoming.visibility = View.VISIBLE
        incoming.alpha = 1f
        incoming.translationX = 0f
        status.visibility = View.GONE

        when (transition) {
            SettingsActivity.FADE -> {
                if (outgoing.drawable == null) {
                    outgoing.visibility = View.INVISIBLE
                } else {
                    outgoing.animate().alpha(0f).setDuration(900)
                        .withEndAction { releaseOutgoing(outgoing, bitmap) }
                        .start()
                }
                incoming.alpha = 0f
                incoming.animate().alpha(1f).setDuration(900).start()
            }
            SettingsActivity.SLIDE -> {
                incoming.translationX = slideDistance
                incoming.animate().translationX(0f).setDuration(700).start()
                if (outgoing.drawable == null) {
                    outgoing.visibility = View.INVISIBLE
                } else {
                    outgoing.animate().translationX(-slideDistance).setDuration(700)
                        .withEndAction { releaseOutgoing(outgoing, bitmap) }
                        .start()
                }
            }
            else -> releaseOutgoing(outgoing, bitmap)
        }
        visibleImage = newIndex
    }

    private fun releaseOutgoing(outgoing: ImageView, activeBitmap: Bitmap) {
        outgoing.visibility = View.INVISIBLE
        outgoing.translationX = 0f
        val oldBitmap = (outgoing.drawable as? BitmapDrawable)?.bitmap
        outgoing.setImageDrawable(null)
        if (oldBitmap != null && oldBitmap !== activeBitmap) oldBitmap.recycle()
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val FAILURE_SKIP_DELAY_MS = 1000L

        internal fun shouldStopAfterFailure(consecutiveFailures: Int, totalPhotos: Int): Boolean {
            if (totalPhotos <= 0) return true
            return consecutiveFailures >= minOf(totalPhotos, MAX_CONSECUTIVE_FAILURES)
        }
    }

    fun clearImages(preserveVisible: Boolean = false) {
        imageViews.forEachIndexed { index, image ->
            image.animate().cancel()
            if (preserveVisible && index == visibleImage && image.drawable != null) {
                image.alpha = 1f
                image.translationX = 0f
                image.visibility = View.VISIBLE
            } else {
                val bitmap = (image.drawable as? BitmapDrawable)?.bitmap
                image.setImageDrawable(null)
                bitmap?.recycle()
                image.visibility = View.INVISIBLE
                image.alpha = 1f
                image.translationX = 0f
            }
        }
        if (!preserveVisible) visibleImage = 0
    }

    private fun footerLabel(context: Context, gravity: Int): TextView =
        TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(5f, 1f, 1f, Color.BLACK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            this.gravity = gravity
        }
}

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
