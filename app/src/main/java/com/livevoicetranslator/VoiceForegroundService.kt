
package com.livevoicetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.pm.ServiceInfo
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
    }

    private var speechRecognizer: SpeechRecognizer? = null

    private var listeningEnabled = false
    private var foregroundStarted = false

    private var sourceLanguage = "en"

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
                startRecognition()
            }

            ACTION_STOP_LISTENING -> {
                listeningEnabled = false

                handler.removeCallbacks(restartRunnable)

                destroyRecognizer()

                updateNotification(
                    "Listening paused"
                )
            }

            ACTION_STOP -> {
                listeningEnabled = false

                handler.removeCallbacks(restartRunnable)

                destroyRecognizer()

                stopForeground(true)
                foregroundStarted = false

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
            ).apply {
                description = "Voice translation running"
            }

            val manager =
                getSystemService(NOTIFICATION_SERVICE)
                        as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        message: String
    ): Notification {

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

        val notification = createNotification(
            "Microphone service ready"
        )

        try {

            if (Build.VERSION.SDK_INT >= 29) {

                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )

            } else {

                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            foregroundStarted = true

        } catch (e: Exception) {

            updateNotification(
                "Microphone service could not start"
            )
        }
    }

    private fun updateNotification(message: String) {

        if (!foregroundStarted) return

        val manager =
            getSystemService(NOTIFICATION_SERVICE)
                    as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            createNotification(message)
        )
    }

    private fun startRecognition() {

        if (!listeningEnabled) return

        if (!foregroundStarted) {
            startForegroundServiceNotification()
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {

            updateNotification(
                "Speech recognition service unavailable"
            )

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

            sourceLanguage = "en"

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "en-US"
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
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    "en-US"
                )

                if (Build.VERSION.SDK_INT >= 34) {

                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,
                        true
                    )

                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        arrayListOf(
                            "en-US",
                            "bn-BD",
                            "ar-SA",
                            "hi-IN",
                            "ur-PK"
                        )
                    )
                }
            }

            speechRecognizer?.startListening(intent)

            updateNotification(
                "Listening for five languages..."
            )

        } catch (e: Exception) {

            updateNotification(
                "Listening could not start. Retrying..."
            )

            scheduleRestart(2000)
        }
    }

    private val recognitionListener =
        object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                updateNotification("🎙 Listening...")
                updatePopupStatus("🎙 Listening...")
            }

            override fun onBeginningOfSpeech() {
                updateNotification("Speech detected...")
            }

            override fun onRmsChanged(rmsdB: Float) {
            }

            override fun onBufferReceived(buffer: ByteArray?) {
            }

            override fun onEndOfSpeech() {
                updateNotification("Processing speech...")
            }

            override fun onError(error: Int) {

                if (!listeningEnabled) return

                val message = when (error) {

                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "কথা পরিষ্কার শোনা যায়নি। আবার চেষ্টা করছি..."

                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "কোনো কথা শোনা যায়নি। আবার শুনছি..."

                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                        "Speech service disconnected. Reconnecting..."

                    SpeechRecognizer.ERROR_NETWORK ->
                        "Internet connection problem"

                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Network timeout. Retrying..."

                    else ->
                        "Speech recognition error: $error"
                }

                updateNotification(message)
                updatePopupStatus(message)

                if (
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    error == SpeechRecognizer.ERROR_CLIENT
                ) {
                    destroyRecognizer()
                }

                scheduleRestart(1500)
            }

            override fun onResults(results: Bundle?) {

                if (!listeningEnabled) return

                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )

                val recognizedText =
                    matches?.firstOrNull()?.trim().orEmpty()

                if (recognizedText.isNotBlank()) {

                    handleRecognizedText(recognizedText)

                } else {

                    updateNotification(
                        "No speech result. Listening again..."
                    )
                }

                scheduleRestart(700)
            }

            override fun onPartialResults(partialResults: Bundle?) {
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
            }

            override fun onLanguageDetection(results: Bundle) {

                if (Build.VERSION.SDK_INT >= 34) {

                    val detected = results.getString(
                        "detected_language"
                    )

                    if (!detected.isNullOrBlank()) {

                        sourceLanguage =
                            normalizeLanguage(detected)
                    }
                }
            }
        }

    private fun normalizeLanguage(language: String): String {

        val code = language.lowercase(Locale.ROOT)

        return when {

            code.startsWith("bn") -> "bn"

            code.startsWith("ar") -> "ar"

            code.startsWith("hi") -> "hi"

            code.startsWith("ur") -> "ur"

            code.startsWith("en") -> "en"

            else -> "en"
        }
    }

    private fun detectLanguageFromText(text: String): String {

        return when {

            text.any { it in '\u0980'..'\u09FF' } ->
                "bn"

            text.any { it in '\u0900'..'\u097F' } ->
                "hi"

            text.any { it in '\u0600'..'\u06FF' } -> {

                if (
                    sourceLanguage == "ur" ||
                    sourceLanguage == "ar"
                ) {
                    sourceLanguage
                } else {
                    "ar"
                }
            }

            else -> sourceLanguage
        }
    }

    private fun handleRecognizedText(text: String) {

        val detectedLanguage =
            detectLanguageFromText(text)

        sourceLanguage = detectedLanguage

        updateNotification(
            "Heard: $text"
        )

        if (detectedLanguage == "bn") {

            showBanglaTranslation(text)

        } else {

            translateToBangla(
                text,
                detectedLanguage
            )
        }
    }

    private fun showBanglaTranslation(text: String) {

        updatePopup(
            text,
            "বাংলা কথা শনাক্ত হয়েছে"
        )

        updateNotification(
            "বাংলা: $text"
        )
    }

    private fun translateToBangla(
        originalText: String,
        language: String
    ) {

        updatePopupStatus(
            "অনুবাদ হচ্ছে..."
        )

        executor.execute {

            var translatedText: String

            try {

                val encodedText = URLEncoder.encode(
                    originalText,
                    "UTF-8"
                )

                val url = URL(
                    "https://api.mymemory.translated.net/get?q=" +
                        encodedText +
                        "&langpair=" +
                        language +
                        "|bn"
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

                translatedText = json
                    .getJSONObject("responseData")
                    .getString("translatedText")

                if (translatedText.isBlank()) {
                    translatedText =
                        "বাংলা অনুবাদ পাওয়া যায়নি।"
                }

            } catch (e: Exception) {

                translatedText =
                    "অনুবাদ করা যায়নি। ইন্টারনেট পরীক্ষা করুন।"
            }

            Handler(Looper.getMainLooper()).post {

                updatePopup(
                    translatedText,
                    "বাংলা অনুবাদ সম্পন্ন"
                )

                updateNotification(
                    "বাংলা: $translatedText"
                )
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

                action =
                    FloatingTranslatorService.ACTION_UPDATE

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
        }
    }

    private fun updatePopupStatus(status: String) {

        try {

            val intent = Intent(
                this,
                FloatingTranslatorService::class.java
            ).apply {

                action =
                    FloatingTranslatorService.ACTION_UPDATE

                putExtra(
                    FloatingTranslatorService.EXTRA_STATUS,
                    status
                )
            }

            startService(intent)

        } catch (e: Exception) {
        }
    }

    private fun scheduleRestart(delay: Long) {

        handler.removeCallbacks(restartRunnable)

        if (listeningEnabled) {
            handler.postDelayed(
                restartRunnable,
                delay
            )
        }
    }

    private fun destroyRecognizer() {

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
