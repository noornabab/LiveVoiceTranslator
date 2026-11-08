
package com.livevoicetranslator

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class FloatingTranslatorService : Service() {

    companion object {
        const val ACTION_SHOW = "FLOATING_SHOW"
        const val ACTION_UPDATE = "FLOATING_UPDATE"
        const val ACTION_HIDE = "FLOATING_HIDE"

        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STATUS = "status"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: LayoutParams

    private var overlayRoot: ScrollView? = null
    private var floatingView: LinearLayout? = null

    private var statusView: TextView? = null
    private var speechView: TextView? = null
    private var inputView: EditText? = null
    private var englishView: TextView? = null
    private var translateButton: Button? = null
    private var minimizeButton: Button? = null

    private var contentVisible = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

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
                if (overlayRoot == null) {
                    showFloatingWindow()
                }
            }

            ACTION_UPDATE -> {
                if (overlayRoot == null) {
                    showFloatingWindow()
                }

                intent.getStringExtra(EXTRA_MESSAGE)?.let {
                    speechView?.text = it
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

        val scrollView = ScrollView(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 18)

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

        minimizeButton = Button(this).apply {
            text = "−"
            textSize = 18f
            setOnClickListener {
                toggleContent()
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

        val divider = dividerView()

        val speechTitle = makeTitle("৫ ভাষা থেকে বাংলা অনুবাদ")

        speechView = TextView(this).apply {
            text = "অন্য পক্ষের কথা এখানে বাংলায় দেখা যাবে..."
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 18)
            minLines = 3
        }

        val secondDivider = dividerView()

        val inputTitle = makeTitle("বাংলা লিখুন → English")

        inputView = EditText(this).apply {
            hint = "এখানে বাংলা লিখুন..."
            textSize = 17f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            setPadding(14, 12, 14, 12)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 12f
            }
        }

        translateButton = Button(this).apply {
            text = "Translate to English"
            textSize = 15f

            setOnClickListener {
                translateBanglaToEnglish()
            }
        }

        val resultTitle = makeTitle("English Translation")

        englishView = TextView(this).apply {
            text = "English translation will appear here..."
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 14)
            minLines = 2
        }

        container.addView(header)
        container.addView(statusView)
        container.addView(divider)
        container.addView(speechTitle)
        container.addView(speechView)
        container.addView(secondDivider)
        container.addView(inputTitle)

        container.addView(
            inputView,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = 8
            }
        )

        container.addView(
            translateButton,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = 8
            }
        )

        container.addView(resultTitle)
        container.addView(englishView)

        scrollView.addView(container)

        params = LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.78f).toInt(),

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                LayoutParams.TYPE_PHONE
            },

            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_NOT_TOUCH_MODAL,

            PixelFormat.TRANSLUCENT

        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 70

            softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        try {
            windowManager.addView(scrollView, params)

            overlayRoot = scrollView
            floatingView = container

        } catch (e: Exception) {
            overlayRoot = null
            floatingView = null
            stopSelf()
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            setBackgroundColor(Color.rgb(50, 90, 130))

            layoutParams = LinearLayout.LayoutParams(
                -1,
                1
            )
        }
    }

    private fun makeTitle(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(100, 220, 255))
            setPadding(0, 14, 0, 6)
        }
    }

    private fun translateBanglaToEnglish() {

        val original =
            inputView?.text?.toString()?.trim().orEmpty()

        if (original.isBlank()) {
            englishView?.text = "আগে বাংলা লিখুন।"
            return
        }

        translateButton?.isEnabled = false
        translateButton?.text = "Translating..."
        englishView?.text = "অনুবাদ হচ্ছে..."

        executor.execute {

            var result: String

            try {
                val encoded = URLEncoder.encode(
                    original,
                    "UTF-8"
                )

                val url = URL(
                    "https://api.mymemory.translated.net/get?q=" +
                        encoded +
                        "&langpair=bn|en"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val response = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()

                val json = JSONObject(response)

                result = json
                    .getJSONObject("responseData")
                    .getString("translatedText")

                if (result.isBlank()) {
                    result = "অনুবাদ পাওয়া যায়নি।"
                }

            } catch (e: Exception) {
                result =
                    "অনুবাদ করা যায়নি। ইন্টারনেট সংযোগ পরীক্ষা করুন।"
            }

            mainHandler.post {
                englishView?.text = result

                translateButton?.isEnabled = true
                translateButton?.text = "Translate to English"
            }
        }
    }

    private fun toggleContent() {

        contentVisible = !contentVisible

        val root = floatingView ?: return

        for (i in 1 until root.childCount) {
            root.getChildAt(i).visibility =
                if (contentVisible) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        minimizeButton?.text =
            if (contentVisible) "−" else "+"
    }

    private fun removeFloatingWindow() {

        overlayRoot?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        overlayRoot = null
        floatingView = null

        statusView = null
        speechView = null
        inputView = null
        englishView = null
        translateButton = null
        minimizeButton = null
    }

    override fun onDestroy() {
        removeFloatingWindow()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
