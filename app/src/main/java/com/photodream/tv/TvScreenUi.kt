package com.photodream.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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

    fun addAction(label: CharSequence, onClick: () -> Unit): TextView =
        createAction(label, false, onClick)

    fun addBottomAction(label: CharSequence, onClick: () -> Unit): TextView {
        panel.addView(View(view.context), LinearLayout.LayoutParams(-1, 0, 1f))
        return createAction(label, true, onClick)
    }

    private fun createAction(
        label: CharSequence,
        isBottomAction: Boolean,
        onClick: () -> Unit
    ): TextView {
        val action = TextView(view.context).apply {
            text = label
            textSize = if (isBottomAction) 13f else 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(
                view.context.dp(12),
                if (isBottomAction) 0 else view.context.dp(10),
                view.context.dp(12),
                if (isBottomAction) 0 else view.context.dp(10)
            )
            minHeight = view.context.dp(if (isBottomAction) 36 else 52)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }
        action.setOnFocusChangeListener { _, hasFocus ->
            action.setTextColor(
                if (action.isEnabled && hasFocus) FOCUS_COLOR
                else if (action.isEnabled) Color.WHITE else 0xFF666666.toInt()
            )
            action.setTypeface(
                null,
                if (action.isEnabled && hasFocus) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL
            )
        }
        panel.addView(
            action,
            if (isBottomAction) {
                LinearLayout.LayoutParams(-1, view.context.dp(36))
            } else {
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = view.context.dp(8)
                }
            }
        )
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

internal fun Context.createIconButton(
    iconResource: Int,
    description: String,
    onClick: () -> Unit
): ImageButton =
    ImageButton(this).apply {
        setImageResource(iconResource)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(Color.TRANSPARENT)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        isFocusable = true
        isFocusableInTouchMode = true
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        setOnFocusChangeListener { _, hasFocus ->
            imageTintList = ColorStateList.valueOf(
                if (hasFocus) FOCUS_COLOR else Color.WHITE
            )
        }
        setOnClickListener { onClick() }
    }

internal fun Activity.openScreensaverSettings() {
    try {
        startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.screensaver_settings_unavailable, Toast.LENGTH_LONG).show()
    }
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private const val FOCUS_COLOR = 0xFFFFC107.toInt()
