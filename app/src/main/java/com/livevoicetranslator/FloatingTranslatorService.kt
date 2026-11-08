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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class FloatingTranslatorService : Service() {

    companion object {
        const val ACTION_SHOW = "com.livevoicetranslator.SHOW"
        const val ACTION_UPDATE = "com.livevoicetranslator.UPDATE"
        const val ACTION_HIDE = "com.livevoicetranslator.HIDE"

        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STATUS = "status"

        private const val PREFS_NAME = "LiveVoiceSettings"
        private const val PREF_LANGUAGE = "speech_language"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: ScrollView
    private lateinit var params: LayoutParams

    private var popupVisible = false
    private var minimized = false

    private var statusView: TextView? = null
    private var speechView: TextView? = null
    private var englishResultView: TextView? = null
    private var contentLayout: LinearLayout? = null

    private var dragX = 0
    private var dragY = 0

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_SHOW -> showPopup()

            ACTION_UPDATE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                val status = intent.getStringExtra(EXTRA_STATUS)

                if (!popupVisible) showPopup()

                if (message != null) {
                    speechView?.text = message
                }

                if (status != null) {
                    statusView?.text = status
                }
            }

            ACTION_HIDE -> hidePopup()
        }

        return START_NOT_STICKY
    }

    private fun showPopup() {
        if (popupVisible) return

        rootView = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 18)
            background = roundedBackground(Color.rgb(25, 30, 42), 18)
        }

        rootView.addView(mainLayout)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 12)
        }

        val title = TextView(this).apply {
            text = "LIVE VOICE TRANSLATOR ↕"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        title.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = event.rawX.toInt() - params.x
                    dragY = event.rawY.toInt() - params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = event.rawX.toInt() - dragX
                    params.y = event.rawY.toInt() - dragY

                    try {
                        windowManager.updateViewLayout(rootView, params)
                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }

        val minimizeButton = Button(this).apply {
            text = "—"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(8, 0, 8, 0)
            setOnClickListener {
                toggleContent()
            }
        }

        val closeButton = Button(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(8, 0, 8, 0)
            setOnClickListener {
                hidePopup()
            }
        }

        header.addView(title)
        header.addView(minimizeButton)
        header.addView(closeButton)

        mainLayout.addView(header)

        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        mainLayout.addView(contentLayout)

        statusView = TextView(this).apply {
            text = "অনুবাদ প্রস্তুত"
            textSize = 13f
            setTextColor(Color.rgb(100, 220, 150))
            setPadding(4, 5, 4, 12)
        }

        contentLayout?.addView(statusView)

        val languageLabel = TextView(this).apply {
            text = "Speech Language"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(4, 8, 4, 6)
        }

        contentLayout?.addView(languageLabel)

        val options = arrayOf(
            "Auto Detect",
            "বাংলা",
            "English",
            "العربية",
            "हिन्दी"
        )

        val languageSpinner = Spinner(this)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            options
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        languageSpinner.adapter = adapter

        val preferences = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )

        val savedLanguage = preferences.getString(
            PREF_LANGUAGE,
            "auto"
        )

        languageSpinner.setSelection(
            when (savedLanguage) {
                "bn-BD" -> 1
                "en-US" -> 2
                "ar-SA" -> 3
                "hi-IN" -> 4
                else -> 0
            }
        )

        languageSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {

                    val selectedLanguage = when (position) {
                        1 -> "bn-BD"
                        2 -> "en-US"
                        3 -> "ar-SA"
                        4 -> "hi-IN"
                        else -> "auto"
                    }

                    preferences.edit()
                        .putString(PREF_LANGUAGE, selectedLanguage)
                        .apply()

                    statusView?.text = when (position) {
                        1 -> "বাংলা ভাষা নির্বাচন করা হয়েছে"
                        2 -> "English selected"
                        3 -> "تم اختيار اللغة العربية"
                        4 -> "हिन्दी भाषा चुनी गई"
                        else -> "Auto Detect নির্বাচন করা হয়েছে"
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }
            }

        contentLayout?.addView(
            languageSpinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val speechLabel = TextView(this).apply {
            text = "Recognized Speech / বাংলা অনুবাদ"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(4, 14, 4, 6)
        }

        contentLayout?.addView(speechLabel)

        speechView = TextView(this).apply {
            text = "কথা বললে এখানে অনুবাদ দেখা যাবে"
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(12, 14, 12, 14)
            background = roundedBackground(Color.rgb(45, 52, 68), 12)
        }

        contentLayout?.addView(
            speechView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val inputLabel = TextView(this).apply {
            text = "বাংলায় লিখুন → English Translation"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(4, 16, 4, 6)
        }

        contentLayout?.addView(inputLabel)

        val banglaInput = EditText(this).apply {
            hint = "এখানে বাংলায় লিখুন"
            textSize = 16f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.WHITE)
        }

        contentLayout?.addView(banglaInput)

        val translateButton = Button(this).apply {
            text = "Translate to English"
            setOnClickListener {
                val input = banglaInput.text.toString().trim()

                if (input.isNotEmpty()) {
                    statusView?.text = "অনুবাদ করা হচ্ছে..."
                    translateBanglaToEnglish(input)
                }
            }
        }

        contentLayout?.addView(translateButton)

        englishResultView = TextView(this).apply {
            text = "English translation will appear here"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(10, 12, 10, 12)
        }

        contentLayout?.addView(englishResultView)

        params = LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            (resources.displayMetrics.heightPixels * 0.55).toInt(),
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
        }

        try {
            windowManager.addView(rootView, params)
            popupVisible = true
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun toggleContent() {
        minimized = !minimized

        contentLayout?.visibility = if (minimized) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun translateBanglaToEnglish(input: String) {

        executor.execute {

            try {
                val encoded = URLEncoder.encode(input, "UTF-8")

                val url = URL(
                    "https://api.mymemory.translated.net/get?q=$encoded&langpair=bn|en"
                )

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val response = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()

                val translated = JSONObject(response)
                    .getJSONObject("responseData")
                    .getString("translatedText")

                android.os.Handler(mainLooper).post {
                    englishResultView?.text = translated
                    statusView?.text = "English translation complete"
                }

            } catch (_: Exception) {
                android.os.Handler(mainLooper).post {
                    englishResultView?.text =
                        "অনুবাদ করা যায়নি। ইন্টারনেট পরীক্ষা করুন।"

                    statusView?.text = "Translation failed"
                }
            }
        }
    }

    private fun hidePopup() {
        if (popupVisible) {
            try {
                windowManager.removeView(rootView)
            } catch (_: Exception) {
            }

            popupVisible = false
        }

        stopSelf()
    }

    override fun onDestroy() {
        if (popupVisible) {
            try {
                windowManager.removeView(rootView)
            } catch (_: Exception) {
            }
        }

        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
