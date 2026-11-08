
package com.livevoicetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class VoiceForegroundService : Service() {

    companion object {
        const val ACTION_PREPARE = "PREPARE_VOICE_SERVICE"
        const val ACTION_START = "START_VOICE_LISTENING"
        const val ACTION_STOP_LISTENING = "STOP_VOICE_LISTENING"
        const val ACTION_STOP = "STOP_VOICE_SERVICE"

        private const val CHANNEL_ID = "live_voice_channel"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS_NAME = "LiveVoiceSettings"
        private const val PREF_LANGUAGE = "speech_language"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningEnabled = false
    private var foregroundStarted = false
    private var recognitionRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val restartRunnable = Runnable {
        if (listeningEnabled) {
            startRecognition()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_PREPARE -> {
                startForegroundServiceNotification()
            }

            ACTION_START -> {

                if (!foregroundStarted) {
                    startForegroundServiceNotification()
                }

                listeningEnabled = true
                handler.removeCallbacks(restartRunnable)
                destroyRecognizer()
                startRecognition()
            }

            ACTION_STOP_LISTENING -> {

                listeningEnabled = false
                handler.removeCallbacks(restartRunnable)
                destroyRecognizer()

                updateNotification("Listening paused")
                updatePopupStatus("কথা শোনা বন্ধ আছে")
            }

            ACTION_STOP -> {

                listeningEnabled = false
                handler.removeCallbacks(restartRunnable)
                destroyRecognizer()

                if (foregroundStarted) {
                    stopForeground(true)
                    foregroundStarted = false
                }

                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Voice Translator",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("Live Voice Translator")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundServiceNotification() {

        if (foregroundStarted) return

        try {

            val notification =
                createNotification("Microphone service ready")

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            foregroundStarted = true

        } catch (e: Exception) {

            updateNotification("Foreground error: ${e.message}")
            updatePopupStatus("Foreground service চালু হয়নি: ${e.message}")
        }
    }

    private fun updateNotification(message: String) {

        if (!foregroundStarted) return

        try {
            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            manager.notify(
                NOTIFICATION_ID,
                createNotification(message)
            )
        } catch (_: Exception) {
        }
    }

    private fun getSelectedLanguage(): String {

        val saved = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).getString(PREF_LANGUAGE, "bn-BD") ?: "bn-BD"

        return when (saved) {
            "bn-BD" -> "bn-BD"
            "en-US" -> "en-US"
            "ar-SA" -> "ar-SA"
            "hi-IN" -> "hi-IN"
            else -> "bn-BD"
        }
    }

    private fun startRecognition() {

        if (!listeningEnabled || recognitionRunning) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError("এই ফোনে Speech Recognition পাওয়া যাচ্ছে না")
            scheduleRestart(4000)
            return
        }

        try {

            if (speechRecognizer == null) {

                speechRecognizer =
                    SpeechRecognizer.createSpeechRecognizer(this)

                speechRecognizer?.setRecognitionListener(
                    recognitionListener
                )
            }

            val language = getSelectedLanguage()

            val recognitionIntent = Intent(
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
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )
            }

            recognitionRunning = true

            val message = when (language) {
                "bn-BD" -> "বাংলা শুনছি..."
                "en-US" -> "English শুনছি..."
                "ar-SA" -> "Arabic শুনছি..."
                "hi-IN" -> "Hindi শুনছি..."
                else -> "কথা শুনছি..."
            }

            updateNotification(message)
            updatePopupStatus("🎙 $message")

            speechRecognizer?.startListening(recognitionIntent)

        } catch (e: Exception) {

            recognitionRunning = false
            showError("শুরু করতে সমস্যা: ${e.message}")

            destroyRecognizer()
            scheduleRestart(3000)
        }
    }

    private val recognitionListener =
        object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                updateNotification("Microphone ready")
                updatePopupStatus("🎙 মাইক্রোফোন প্রস্তুত। এখন কথা বলুন...")
            }

            override fun onBeginningOfSpeech() {
                updateNotification("Speech detected")
                updatePopupStatus("✅ আপনার কথা শোনা যাচ্ছে...")
            }

            override fun onRmsChanged(rmsdB: Float) {
            }

            override fun onBufferReceived(buffer: ByteArray?) {
            }

            override fun onEndOfSpeech() {
                updateNotification("Processing speech...")
                updatePopupStatus("কথা পাওয়া গেছে। অনুবাদ হচ্ছে...")
            }

            override fun onError(error: Int) {

                recognitionRunning = false

                if (!listeningEnabled) return

                val message = when (error) {

                    SpeechRecognizer.ERROR_AUDIO ->
                        "ERROR_AUDIO: অডিও গ্রহণে সমস্যা"

                    SpeechRecognizer.ERROR_CLIENT ->
                        "ERROR_CLIENT: Recognition client সমস্যা"

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "ERROR_PERMISSION: Microphone permission নেই"

                    SpeechRecognizer.ERROR_NETWORK ->
                        "ERROR_NETWORK: ইন্টারনেট সমস্যা"

                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "ERROR_NETWORK_TIMEOUT: ইন্টারনেটের সময় শেষ"

                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "ERROR_NO_MATCH: কোনো কথা শনাক্ত হয়নি"

                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "ERROR_SPEECH_TIMEOUT: কথা শোনা যায়নি"

                    SpeechRecognizer.ERROR_SERVER ->
                        "ERROR_SERVER: Google Speech Server সমস্যা"

                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                        "ERROR_SERVER_DISCONNECTED: Speech Service বিচ্ছিন্ন"

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "ERROR_RECOGNIZER_BUSY: Recognition ব্যস্ত"

                    else ->
                        "UNKNOWN ERROR: $error"
                }

                showError(message)

                if (
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                ) {
                    destroyRecognizer()
                }

                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> 2000L
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 2000L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 4000L
                    else -> 3000L
                }

                scheduleRestart(delay)
            }

            override fun onResults(results: Bundle?) {

                recognitionRunning = false

                if (!listeningEnabled) return

                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )

                val text = matches?.firstOrNull()?.trim().orEmpty()

                if (text.isNotBlank()) {

                    updateNotification("Recognized: $text")
                    handleRecognizedText(text)

                } else {
                    showError("RESULT_EMPTY: কোনো লেখা পাওয়া যায়নি")
                }

                scheduleRestart(1200)
            }

            override fun onPartialResults(partialResults: Bundle?) {

                if (!listeningEnabled) return

                val matches = partialResults?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )

                val text = matches?.firstOrNull()?.trim().orEmpty()

                if (text.isNotBlank()) {
                    updateNotification("Hearing: $text")
                    updatePopupStatus("শোনা যাচ্ছে: $text")
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
            }
        }

    private fun showError(message: String) {
        updateNotification(message)
        updatePopupStatus("⚠ $message")
    }

    private fun detectLanguage(text: String): String {

        return when {
            text.any { it in '\u0980'..'\u09FF' } -> "bn"
            text.any { it in '\u0900'..'\u097F' } -> "hi"
            text.any { it in '\u0600'..'\u06FF' } -> "ar"

            else -> when (getSelectedLanguage()) {
                "bn-BD" -> "bn"
                "en-US" -> "en"
                "ar-SA" -> "ar"
                "hi-IN" -> "hi"
                else -> "bn"
            }
        }
    }

    private fun handleRecognizedText(text: String) {

        val cleanText = text.trim()

        if (cleanText.isBlank()) return

        val language = detectLanguage(cleanText)

        if (language == "bn") {

            updatePopup(
                cleanText,
                "বাংলা কথা শনাক্ত হয়েছে"
            )

        } else {

            translateToBangla(cleanText, language)
        }
    }

    private fun translateToBangla(
        originalText: String,
        language: String
    ) {

        updatePopupStatus("বাংলা অনুবাদ হচ্ছে...")

        executor.execute {

            var connection: HttpURLConnection? = null

            try {

                val encoded = URLEncoder.encode(
                    originalText,
                    "UTF-8"
                )

                val url = URL(
                    "https://api.mymemory.translated.net/get?q=$encoded&langpair=$language|bn"
                )

                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val response = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val json = JSONObject(response)

                val translated = json
                    .getJSONObject("responseData")
                    .getString("translatedText")

                handler.post {

                    if (!listeningEnabled) return@post

                    if (translated.isNotBlank()) {
                        updatePopup(
                            translated,
                            "বাংলা অনুবাদ সম্পন্ন"
                        )
                    } else {
                        showError("অনুবাদ পাওয়া যায়নি")
                    }
                }

            } catch (e: Exception) {

                handler.post {
                    if (listeningEnabled) {
                        showError("অনুবাদ ব্যর্থ: ${e.message}")
                    }
                }

            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun updatePopup(
        message: String,
        status: String
    ) {

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

        } catch (_: Exception) {
        }
    }

    private fun updatePopupStatus(status: String) {

        try {

            val intent = Intent(
                this,
                FloatingTranslatorService::class.java
            ).apply {
                action = FloatingTranslatorService.ACTION_UPDATE

                putExtra(
                    FloatingTranslatorService.EXTRA_STATUS,
                    status
                )
            }

            startService(intent)

        } catch (_: Exception) {
        }
    }

    private fun scheduleRestart(delay: Long) {

        handler.removeCallbacks(restartRunnable)

        if (listeningEnabled) {
            handler.postDelayed(restartRunnable, delay)
        }
    }

    private fun destroyRecognizer() {

        recognitionRunning = false

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null
    }

    override fun onDestroy() {

        listeningEnabled = false
        handler.removeCallbacks(restartRunnable)
        destroyRecognizer()
        executor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
