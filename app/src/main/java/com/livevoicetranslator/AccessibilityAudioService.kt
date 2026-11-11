
package com.livevoicetranslator

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class AccessibilityAudioService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "LiveVoiceSettings"
        private const val PREF_LANGUAGE = "speech_language"
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var callActive = false

    private var windowManager: WindowManager? = null
    private var statusView: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val translationExecutor =
        Executors.newSingleThreadExecutor()

    private var restartRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        showStatusOverlay("Live Voice Translator প্রস্তুত")
        startPhoneMonitoring()
    }

    // PHONE MONITORING

    private fun startPhoneMonitoring() {
        try {
            if (
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                updateStatus("PHONE PERMISSION REQUIRED")
                return
            }

            telephonyManager =
                getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            phoneListener = object : PhoneStateListener() {

                override fun onCallStateChanged(
                    state: Int,
                    phoneNumber: String?
                ) {
                    super.onCallStateChanged(state, phoneNumber)

                    when (state) {

                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            if (callActive) return

                            callActive = true
                            showStatusOverlay("কল সংযুক্ত")
                            showTranslatorPopup()
                            startSpeechRecognition()
                        }

                        TelephonyManager.CALL_STATE_IDLE -> {
                            if (!callActive) return

                            callActive = false
                            stopSpeechRecognition()
                            hideTranslatorPopup()
                            updateStatus("কল শেষ")
                        }

                        TelephonyManager.CALL_STATE_RINGING -> {
                            updateStatus("ইনকামিং কল")
                        }
                    }
                }
            }

            telephonyManager?.listen(
                phoneListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )

            if (
                telephonyManager?.callState ==
                TelephonyManager.CALL_STATE_OFFHOOK
            ) {
                callActive = true
                showTranslatorPopup()
                startSpeechRecognition()
            }

        } catch (e: Exception) {
            updateStatus("PHONE ERROR: ${e.message}")
        }
    }

    // POPUP CONTROL

    private fun showTranslatorPopup() {
        try {
            val intent = Intent(
                this,
                FloatingTranslatorService::class.java
            ).apply {
                action = FloatingTranslatorService.ACTION_SHOW
            }

            startService(intent)

        } catch (e: Exception) {
            updateStatus("POPUP ERROR: ${e.message}")
        }
    }

    private fun hideTranslatorPopup() {
        try {
            val intent = Intent(
                this,
                FloatingTranslatorService::class.java
            ).apply {
                action = FloatingTranslatorService.ACTION_HIDE
            }

            startService(intent)

        } catch (_: Exception) {
        }
    }

    // SELECTED LANGUAGE

    private fun getSelectedLanguage(): String {
        val preferences = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )

        return preferences.getString(
            PREF_LANGUAGE,
            "bn-BD"
        ) ?: "bn-BD"
    }

    // SPEECH RECOGNITION

    private fun startSpeechRecognition() {
        if (!callActive) return

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus("MIC PERMISSION REQUIRED")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("Speech Recognition service পাওয়া যায়নি")
            return
        }

        try {
            stopSpeechRecognition()

            speechRecognizer =
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(this)
                }

            speechRecognizer?.setRecognitionListener(
                recognitionListener
            )

            updateStatus("Speech Recognition চালু হচ্ছে...")
            startListening()

        } catch (e: Exception) {
            updateStatus("SPEECH ERROR: ${e.message}")
            scheduleRecognitionRestart(1500)
        }
    }

    // RECOGNITION LISTENER

    private val recognitionListener =
        object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                updateStatus("কথা শোনার জন্য প্রস্তুত")
            }

            override fun onBeginningOfSpeech() {
                updateStatus("কথা শনাক্ত হচ্ছে...")
            }

            override fun onRmsChanged(rmsdB: Float) {
            }

            override fun onBufferReceived(buffer: ByteArray?) {
            }

            override fun onEndOfSpeech() {
                updateStatus("কথা প্রক্রিয়াকরণ হচ্ছে...")
            }

            override fun onError(error: Int) {
                updateStatus("Speech Recognition Error: $error")
                scheduleRecognitionRestart(1200)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )

                val recognizedText =
                    matches?.firstOrNull()?.trim().orEmpty()

                if (recognizedText.isNotBlank()) {
                    processRecognizedSpeech(recognizedText)
                }

                scheduleRecognitionRestart(600)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )

                val partialText =
                    matches?.firstOrNull()?.trim().orEmpty()

                if (partialText.isNotBlank()) {
                    sendPopupUpdate(
                        "মূল কথা: $partialText",
                        "কথা শনাক্ত হচ্ছে..."
                    )
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
            }
        }

    // START LISTENING

    private fun startListening() {
        if (!callActive) return

        val recognizer = speechRecognizer ?: return

        try {
            val language = getSelectedLanguage()

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    language
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    language
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )

                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    false
                )

                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )
            }

            recognizer.startListening(intent)

        } catch (e: Exception) {
            updateStatus("LISTENING ERROR: ${e.message}")
            scheduleRecognitionRestart(1500)
        }
    }

    // RESTART RECOGNITION

    private fun scheduleRecognitionRestart(delay: Long) {
        restartRunnable?.let {
            mainHandler.removeCallbacks(it)
        }

        val runnable = Runnable {
            if (callActive) {
                try {
                    speechRecognizer?.cancel()
                    startListening()
                } catch (e: Exception) {
                    updateStatus("RESTART ERROR: ${e.message}")
                }
            }
        }

        restartRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    // PROCESS SPEECH

    private fun processRecognizedSpeech(text: String) {
        val language = getSelectedLanguage()

        if (language == "bn-BD") {
            sendPopupUpdate(
                "মূল কথা: $text\n\nবাংলা: $text",
                "বাংলা কথা শনাক্ত হয়েছে"
            )
            return
        }

        sendPopupUpdate(
            "মূল কথা: $text\n\nবাংলা: অনুবাদ হচ্ছে...",
            "বাংলা অনুবাদ হচ্ছে..."
        )

        translateToBangla(text, language)
    }

    // TRANSLATE TO BANGLA

    private fun translateToBangla(
        originalText: String,
        sourceLanguage: String
    ) {
        try {
            translationExecutor.execute {

                var connection: HttpURLConnection? = null

                try {
                    val sourceCode = when (sourceLanguage) {
                        "en-US" -> "en"
                        "ar-SA" -> "ar"
                        "hi-IN" -> "hi"
                        else -> "en"
                    }

                    val encoded = URLEncoder.encode(
                        originalText,
                        "UTF-8"
                    )

                    val url = URL(
                        "https://api.mymemory.translated.net/get" +
                            "?q=$encoded&langpair=$sourceCode|bn"
                    )

                    connection =
                        url.openConnection() as HttpURLConnection

                    connection.requestMethod = "GET"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000

                    val responseCode = connection.responseCode

                    if (responseCode != 200) {
                        throw Exception("HTTP $responseCode")
                    }

                    val response = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                    val translated = JSONObject(response)
                        .getJSONObject("responseData")
                        .optString("translatedText", "")
                        .trim()

                    if (translated.isBlank()) {
                        throw Exception("Empty translation")
                    }

                    mainHandler.post {
                        if (!callActive) return@post

                        sendPopupUpdate(
                            "মূল কথা: $originalText\n\nবাংলা অনুবাদ: $translated",
                            "বাংলা অনুবাদ সম্পন্ন"
                        )
                    }

                } catch (e: Exception) {
                    mainHandler.post {
                        if (!callActive) return@post

                        sendPopupUpdate(
                            "মূল কথা: $originalText\n\nবাংলা অনুবাদ পাওয়া যায়নি।",
                            "TRANSLATION FAILED"
                        )
                    }

                } finally {
                    connection?.disconnect()
                }
            }
        } catch (e: Exception) {
            updateStatus("TRANSLATION QUEUE ERROR: ${e.message}")
        }
    }

    // UPDATE POPUP

    private fun sendPopupUpdate(
        message: String,
        status: String
    ) {
        if (!callActive) return

        try {
            val intent = Intent(
                this,
                FloatingTranslatorService::class.java
            ).apply {

                action = FloatingTranslatorService.ACTION_UPDATE

                putExtra(
                    FloatingTranslatorService.EXTRA_MESSAGE,
                    message
                )

                putExtra(
                    FloatingTranslatorService.EXTRA_STATUS,
                    status
                )
            }

            startService(intent)

        } catch (e: Exception) {
            updateStatus("POPUP UPDATE ERROR: ${e.message}")
        }
    }

    // STATUS OVERLAY

    private fun showStatusOverlay(message: String) {
        if (statusView != null) {
            updateStatus(message)
            return
        }

        try {
            val view = TextView(this).apply {
                text = message
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(35, 75, 110))
                setPadding(14, 9, 14, 9)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 45
            }

            windowManager?.addView(view, params)
            statusView = view

        } catch (e: Exception) {
            updateStatus("OVERLAY ERROR: ${e.message}")
        }
    }

    private fun updateStatus(message: String) {
        mainHandler.post {
            statusView?.text = message

            try {
                val intent = Intent(
                    this,
                    FloatingTranslatorService::class.java
                ).apply {

                    action = FloatingTranslatorService.ACTION_UPDATE

                    putExtra(
                        FloatingTranslatorService.EXTRA_STATUS,
                        message
                    )
                }

                startService(intent)

            } catch (_: Exception) {
            }
        }
    }

    // STOP RECOGNITION

    private fun stopSpeechRecognition() {
        restartRunnable?.let {
            mainHandler.removeCallbacks(it)
        }

        restartRunnable = null

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null
    }

    // REMOVE OVERLAY

    private fun removeStatusOverlay() {
        try {
            statusView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        statusView = null
    }

    // ACCESSIBILITY LIFECYCLE

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No screen content is collected.
    }

    override fun onInterrupt() {
        callActive = false
        stopSpeechRecognition()
    }

    override fun onDestroy() {
        callActive = false

        stopSpeechRecognition()
        hideTranslatorPopup()

        try {
            phoneListener?.let {
                telephonyManager?.listen(
                    it,
                    PhoneStateListener.LISTEN_NONE
                )
            }
        } catch (_: Exception) {
        }

        removeStatusOverlay()
        translationExecutor.shutdownNow()

        super.onDestroy()
    }
}
