// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
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

private const val DISABLED_COLOR = 0xFF666666.toInt()

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
        setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        background = null
        minimumWidth = ctx.dp(48)
        minimumHeight = ctx.dp(48)
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

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
