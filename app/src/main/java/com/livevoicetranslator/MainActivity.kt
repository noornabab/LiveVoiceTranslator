
package com.livevoicetranslator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import android.text.Html
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var inputText: EditText
    private lateinit var statusText: TextView
    private lateinit var resultLayout: LinearLayout
    private lateinit var speechRecognizer: SpeechRecognizer

    private var telephonyManager: TelephonyManager? = null
    private var callListener: PhoneStateListener? = null
    private var pendingBackgroundStart = false

    private val executor = Executors.newFixedThreadPool(4)

    private val languages = listOf(
        "English" to "en",
        "Hindi" to "hi",
        "Urdu" to "ur",
        "Arabic" to "ar"
    )

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                if (pendingBackgroundStart) {
                    pendingBackgroundStart = false
                    requestNotificationAndStart()
                } else {
                    startListening()
                }
            } else {
                statusText.text = "Microphone permission প্রয়োজন"
            }
        }

    private val notificationLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            startBackgroundService()
        }

    private val callPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                registerCallListener()
                statusText.text = "Phone Call Detection চালু হয়েছে"
            } else {
                statusText.text =
                    "Phone Call Detection-এর Permission দেওয়া হয়নি"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer.setRecognitionListener(speechListener)
        } else {
            statusText.text = "Speech recognition unavailable"
        }

        checkCallPermission()
    }

    private fun checkCallPermission() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            registerCallListener()
        } else {
            callPermissionLauncher.launch(
                Manifest.permission.READ_PHONE_STATE
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun registerCallListener() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            telephonyManager =
                getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager

            callListener = object : PhoneStateListener() {

                override fun onCallStateChanged(
                    state: Int,
                    phoneNumber: String?
                ) {
                    super.onCallStateChanged(state, phoneNumber)

                    runOnUiThread {
                        when (state) {

                            TelephonyManager.CALL_STATE_RINGING -> {
                                statusText.text =
                                    "📞 INCOMING CALL - ফোনে কল এসেছে"
                            }

                            TelephonyManager.CALL_STATE_OFFHOOK -> {
                                statusText.text =
                                    "📞 CALL CONNECTED - Speaker চালু করুন"
                            }

                            TelephonyManager.CALL_STATE_IDLE -> {
                                statusText.text =
                                    "CALL ENDED / ফোন এখন Idle"
                            }
                        }
                    }
                }
            }

            telephonyManager?.listen(
                callListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )

        } catch (e: Exception) {
            statusText.text =
                "Call Detection Error: ${e.message}"
        }
    }

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "LIVE VOICE TRANSLATOR"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 75, 130))
            setPadding(5, 15, 5, 10)
        }

        val subtitle = TextView(this).apply {
            text = "বাংলা কথা বলুন, চার ভাষায় অনুবাদ করুন"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(5, 5, 5, 25)
        }

        val inputLabel = TextView(this).apply {
            text = "বাংলা লেখা / Voice Transcript:"
            textSize = 17f
            setTextColor(Color.BLACK)
        }

        inputText = EditText(this).apply {
            hint = "এখানে বাংলায় লিখুন অথবা কথা বলুন..."
            textSize = 17f
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            setPadding(14, 12, 14, 12)
            setBackgroundColor(Color.rgb(245, 245, 245))
        }

        val listenButton = Button(this).apply {
            text = "🎙 START LISTENING"
            setOnClickListener {
                checkPermissionAndListen()
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP LISTENING"
            setOnClickListener {
                if (::speechRecognizer.isInitialized) {
                    speechRecognizer.stopListening()
                    statusText.text = "Listening stopped"
                }
            }
        }

        val backgroundButton = Button(this).apply {
            text = "▶ START BACKGROUND"
            setOnClickListener {
                checkPermissionAndStartBackground()
            }
        }

        val stopBackgroundButton = Button(this).apply {
            text = "■ STOP BACKGROUND"
            setOnClickListener {
                stopBackgroundService()
            }
        }

        val translateButton = Button(this).apply {
            text = "TRANSLATE"
            setOnClickListener {
                translateText()
            }
        }

        statusText = TextView(this).apply {
            text = "অনুবাদের জন্য প্রস্তুত"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(5, 15, 5, 15)
        }

        val resultTitle = TextView(this).apply {
            text = "TRANSLATION RESULTS"
            textSize = 18f
            setTextColor(Color.rgb(20, 75, 130))
            setPadding(5, 15, 5, 12)
        }

        resultLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(inputLabel)
        content.addView(inputText)
        content.addView(listenButton)
        content.addView(stopButton)
        content.addView(backgroundButton)
        content.addView(stopBackgroundButton)
        content.addView(translateButton)
        content.addView(statusText)
        content.addView(resultTitle)
        content.addView(resultLayout)

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun checkPermissionAndListen() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            pendingBackgroundStart = false
            permissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun checkPermissionAndStartBackground() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBackgroundStart = true
            permissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
            return
        }

        requestNotificationAndStart()
    }

    private fun requestNotificationAndStart() {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            startBackgroundService()
        }
    }

    private fun startBackgroundService() {

        try {
            val serviceIntent = Intent(
                this,
                VoiceForegroundService::class.java
            ).apply {
                action = VoiceForegroundService.ACTION_START
            }

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            statusText.text =
                "Background microphone চালু করার অনুরোধ পাঠানো হয়েছে"

        } catch (e: Exception) {
            statusText.text =
                "Background service চালু করা যায়নি: ${e.message}"
        }
    }

    private fun stopBackgroundService() {

        val serviceIntent = Intent(
            this,
            VoiceForegroundService::class.java
        ).apply {
            action = VoiceForegroundService.ACTION_STOP
        }

        startService(serviceIntent)

        statusText.text =
            "Background service বন্ধ করার অনুরোধ পাঠানো হয়েছে"
    }

    private fun startListening() {

        if (!::speechRecognizer.isInitialized) {
            statusText.text = "Speech recognition unavailable"
            return
        }

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "bn-BD"
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                "bn-BD"
            )
            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            )
        }

        try {
            statusText.text = "আপনার কথা শুনছি..."
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            statusText.text = "Listening শুরু করা যায়নি"
        }
    }

    private val speechListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            statusText.text = "বলুন, আপনার কথা শুনছি..."
        }

        override fun onBeginningOfSpeech() {
            statusText.text = "কথা শনাক্ত হচ্ছে..."
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            statusText.text = "কথা শনাক্ত করা হচ্ছে..."
        }

        override fun onError(error: Int) {
            statusText.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH ->
                    "কোনো কথা শনাক্ত হয়নি"

                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "কোনো কথা শোনা যায়নি"

                SpeechRecognizer.ERROR_NETWORK ->
                    "Network error"

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Microphone permission নেই"

                else ->
                    "Voice recognition error: $error"
            }
        }

        override fun onResults(results: Bundle?) {

            val matches = results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

            if (!matches.isNullOrEmpty()) {
                inputText.setText(matches[0])
                inputText.setSelection(inputText.text.length)
                statusText.text = "কথা লেখায় রূপান্তর সম্পন্ন"
            } else {
                statusText.text = "কথা শনাক্ত করা যায়নি"
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {

            val matches = partialResults?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

            if (!matches.isNullOrEmpty()) {
                inputText.setText(matches[0])
                inputText.setSelection(inputText.text.length)
            }
        }

        override fun onEvent(
            eventType: Int,
            params: Bundle?
        ) {}
    }

    private fun translateText() {

        val original = inputText.text.toString().trim()

        if (original.isEmpty()) {
            inputText.error = "বাংলা লেখা লিখুন"
            return
        }

        resultLayout.removeAllViews()
        statusText.text = "অনুবাদ হচ্ছে..."

        val loading = TextView(this).apply {
            text = "Please wait..."
            textSize = 16f
            setPadding(10, 15, 10, 15)
            setTextColor(Color.GRAY)
        }

        resultLayout.addView(loading)

        languages.forEach { language ->

            executor.execute {

                val translated = translate(
                    original,
                    language.second
                )

                runOnUiThread {

                    if (isFinishing || isDestroyed) return@runOnUiThread

                    if (resultLayout.indexOfChild(loading) >= 0) {
                        resultLayout.removeView(loading)
                    }

                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(14, 14, 14, 14)
                        setBackgroundColor(
                            Color.rgb(242, 246, 250)
                        )
                    }

                    val languageTitle = TextView(this).apply {
                        text = language.first
                        textSize = 18f
                        setTextColor(Color.rgb(20, 75, 130))
                    }

                    val translatedText =
                        translated ?: "অনুবাদ পাওয়া যায়নি"

                    val result = TextView(this).apply {
                        text = translatedText
                        textSize = 17f
                        setTextColor(Color.BLACK)
                        setPadding(0, 10, 0, 10)
                    }

                    val copyButton = Button(this).apply {
                        text = "COPY ${language.first.uppercase()}"
                        isEnabled = translated != null

                        setOnClickListener {

                            val clipboard =
                                getSystemService(
                                    Context.CLIPBOARD_SERVICE
                                ) as ClipboardManager

                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    language.first,
                                    translatedText
                                )
                            )

                            Toast.makeText(
                                this@MainActivity,
                                "${language.first} copied",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    card.addView(languageTitle)
                    card.addView(result)
                    card.addView(copyButton)

                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )

                    params.setMargins(0, 6, 0, 6)
                    resultLayout.addView(card, params)

                    if (resultLayout.childCount >= languages.size) {
                        statusText.text = "অনুবাদ সম্পন্ন"
                    }
                }
            }
        }
    }

    private fun translate(
        text: String,
        targetLanguage: String
    ): String? {

        var connection: HttpURLConnection? = null

        return try {

            val query = URLEncoder.encode(text, "UTF-8")

            val address =
                "https://api.mymemory.translated.net/get" +
                "?q=$query&langpair=bn%7C$targetLanguage"

            connection = URL(address)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(response)

            val translated = json
                .optJSONObject("responseData")
                ?.optString("translatedText")

            if (translated.isNullOrBlank()) {
                null
            } else {
                Html.fromHtml(
                    translated,
                    Html.FROM_HTML_MODE_LEGACY
                ).toString()
            }

        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {

        try {
            callListener?.let {
                telephonyManager?.listen(
                    it,
                    PhoneStateListener.LISTEN_NONE
                )
            }
        } catch (_: Exception) {
        }

        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }

        executor.shutdownNow()
        super.onDestroy()
    }
}
