package com.photodream.tv

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal class PhotoClock(
    private val context: Context,
    private val handler: Handler,
    private val alignment: Int = Gravity.END
) {
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = alignment
    }

    private val date = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        setShadowLayer(5f, 1f, 1f, Color.BLACK)
        gravity = alignment
    }
    private val time = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 38f
        setShadowLayer(5f, 1f, 1f, Color.BLACK)
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
        date.text = DateFormat.format("EEE, MMM d", now)
            .toString()
            .uppercase(Locale.getDefault())
        time.text = DateFormat.format(
            if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm",
            now
        )
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
