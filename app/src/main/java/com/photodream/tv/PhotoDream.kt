package com.photodream.tv

import android.graphics.Color
import android.service.dreams.DreamService
import android.view.Gravity
import android.widget.TextView

/** Screensaver entry point. Placeholder: a dim line of text on black until the photo sources exist. */
class PhotoDream : DreamService() {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // any key wakes the TV back to where it was
        isFullscreen = true
        isScreenBright = false
        val view = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(0xFF888888.toInt())
            textSize = 28f
            gravity = Gravity.CENTER
            setText(R.string.placeholder)
        }
        setContentView(view)
    }
}
