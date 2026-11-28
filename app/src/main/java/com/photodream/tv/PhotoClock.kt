// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

internal class PhotoClock(
    private val context: Context,
    private val handler: Handler,
    private val alignment: Int = Gravity.CENTER_HORIZONTAL
) {
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = alignment
    }

    private val date = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = TvDesignTokens.fontLight
        includeFontPadding = false
        setShadowLayer(8f, 0f, 2f, 0xDD000000.toInt())
        gravity = alignment
    }
    private val time = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 40f
        typeface = TvDesignTokens.fontClock
        includeFontPadding = false
        setShadowLayer(8f, 0f, 2f, 0xDD000000.toInt())
        gravity = alignment
    }
    private val update = object : Runnable {
        override fun run() {
            updateText()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, MINUTE_MILLIS - now % MINUTE_MILLIS)
        }
    }

    init {
        view.addView(date)
        view.addView(time)
    }

    fun start() {
        handler.removeCallbacks(update)
        update.run()
    }

    fun stop() {
        handler.removeCallbacks(update)
    }

    private fun updateText() {
        val now = System.currentTimeMillis()
        date.text = DateFormat.format("EEE, MMM d", now).toString()
        time.text = DateFormat.format(
            if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm",
            now
        )
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
