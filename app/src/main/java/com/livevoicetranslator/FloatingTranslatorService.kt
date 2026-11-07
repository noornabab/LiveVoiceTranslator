
package com.livevoicetranslator

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View

class FloatingTranslatorService : Service() {

    companion object {
        const val ACTION_SHOW = "FLOATING_SHOW"
        const val ACTION_UPDATE = "FLOATING_UPDATE"
        const val ACTION_HIDE = "FLOATING_HIDE"

        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STATUS = "status"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: LinearLayout? = null
    private var messageView: TextView? = null
    private var statusView: TextView? = null
    private var expanded = true

    private lateinit var params: LayoutParams

    override fun onCreate() {
        super.onCreate()
        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_SHOW -> {
                if (floatingView == null) {
                    showFloatingWindow()
                }
            }

            ACTION_UPDATE -> {
                intent.getStringExtra(EXTRA_MESSAGE)?.let {
                    messageView?.text = it
                }

                intent.getStringExtra(EXTRA_STATUS)?.let {
                    statusView?.text = it
                }
            }

            ACTION_HIDE -> {
                removeFloatingWindow()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun showFloatingWindow() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = GradientDrawable().apply {
                setColor(Color.rgb(9, 30, 65))
                cornerRadius = 24f
                setStroke(2, Color.rgb(35, 130, 210))
            }
            elevation = 12f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "LIVE VOICE TRANSLATOR"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val minimizeButton = Button(this).apply {
            text = "−"
            textSize = 18f
            setOnClickListener {
                toggleSize()
            }
        }

        val closeButton = Button(this).apply {
            text = "✕"
            setOnClickListener {
                removeFloatingWindow()
                stopSelf()
            }
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(minimizeButton)
        header.addView(closeButton)

        statusView = TextView(this).apply {
            text = "🎙 Listening..."
            textSize = 13f
            setTextColor(Color.rgb(100, 220, 255))
            setPadding(0, 10, 0, 12)
        }

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(50, 90, 130))
        }

        val translationTitle = TextView(this).apply {
            text = "বাংলা অনুবাদ"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 8)
        }

        messageView = TextView(this).apply {
            text = "অপর পাশের কথা এখানে দেখা যাবে..."
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 12)
            minLines = 3
        }

        container.addView(header)
        container.addView(statusView)
        container.addView(
            divider,
            LinearLayout.LayoutParams(-1, 1)
        )
        container.addView(translationTitle)
        container.addView(messageView)

        floatingView = container

        params = LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                LayoutParams.TYPE_PHONE
            },
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            floatingView = null
            stopSelf()
        }
    }

    private fun toggleSize() {

        val view = floatingView ?: return

        expanded = !expanded

        messageView?.visibility =
            if (expanded) View.VISIBLE else View.GONE

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun removeFloatingWindow() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        floatingView = null
        messageView = null
        statusView = null
    }

    override fun onDestroy() {
        removeFloatingWindow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
