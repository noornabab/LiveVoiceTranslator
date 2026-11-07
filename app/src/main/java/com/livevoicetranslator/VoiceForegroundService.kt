
package com.livevoicetranslator

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class VoiceForegroundService : Service() {

    companion object {
        const val ACTION_START = "START_VOICE_SERVICE"
        const val ACTION_STOP = "STOP_VOICE_SERVICE"

        private const val CHANNEL_ID = "voice_translator_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val handler = Handler(Looper.getMainLooper())

    private val executor = Executors.newSingleThreadExecutor()

    private val translationId = AtomicInteger(0)

    private var speechRecognizer: SpeechRecognizer? = null

    private var listening = false
    private var sourceLanguage = "en"
    private var lastPartialText = ""

    private var restartRunnable: Runnable? = null

    private val partialTranslation = Runnable {
        if (lastPartialText.isNotBlank()) {
            translateText(lastPartialText, sourceLanguage)
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

        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Starting automatic translation..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Starting automatic translation...")
                )
            }

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                updateNotification("Speech Recognition unavailable")
                stopSelf()
                return START_NOT_STICKY
            }

            if (!listening) {
                listening = true
                startRecognition(true)
            }

        } catch (e: Exception) {
            updateNotification("Service Error: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startRecognition(recreate: Boolean = false) {

        if (!listening) return

        try {

            if (recreate || speechRecognizer == null) {

                speechRecognizer?.destroy()

                speechRecognizer =
                    SpeechRecognizer.createSpeechRecognizer(this)

                speechRecognizer?.setRecognitionListener(
                    createRecognitionListener()
                )
            }

            val speechIntent = Intent(
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

                if (Build.VERSION.SDK_INT >= 34) {

                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,
                        true
                    )

                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        arrayListOf(
                            "en-US",
                            "ar-SA",
                            "hi-IN",
                            "ur-PK",
                            "bn-BD"
                        )
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        true
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_MAX_SWITCHES,
                        10
                    )
                }
            }

            updateNotification(
                "Automatic Listening: 5 Languages"
            )

            speechRecognizer?.startListening(speechIntent)

        } catch (e: Exception) {

            updateNotification(
                "Recognition Error: ${e.message}"
            )

            scheduleRestart(11)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {

        return object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                updateNotification(
                    "Listening: English / Arabic / Hindi / Urdu / Bangla"
                )
            }

            override fun onBeginningOfSpeech() {
                updateNotification("Speech detected...")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                updateNotification("Processing speech...")
            }

            override fun onError(error: Int) {

                updateNotification(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "No speech match. Listening again..."

                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                            "Server disconnected. Reconnecting..."

                        SpeechRecognizer.ERROR_NETWORK ->
                            "Network error. Checking connection..."

                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Network timeout. Retrying..."

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission required"

                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "Recognizer busy. Retrying..."

                        else ->
                            "Speech Error Code: $error"
                    }
                )

                if (listening) {
                    scheduleRestart(error)
                }
            }

            override fun onLanguageDetection(results: Bundle) {

                if (Build.VERSION.SDK_INT >= 34) {

                    val detected = results.getString(
                        SpeechRecognizer.DETECTED_LANGUAGE
                    )

                    if (!detected.isNullOrBlank()) {

                        sourceLanguage = languageCode(detected)

                        updateNotification(
                            "Detected language: $detected"
                        )
                    }
                }
            }

            override fun onPartialResults(
                partialResults: Bundle?
            ) {

                val text = partialResults
                    ?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    ?.firstOrNull()

                if (!text.isNullOrBlank()) {

                    lastPartialText = text

                    updateNotification(
                        "Heard: $text"
                    )

                    handler.removeCallbacks(partialTranslation)

                    handler.postDelayed(
                        partialTranslation,
                        1200
                    )
                }
            }

            override fun onResults(results: Bundle?) {

                handler.removeCallbacks(partialTranslation)

                val text = results
                    ?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    ?.firstOrNull()

                if (!text.isNullOrBlank()) {

                    lastPartialText = ""

                    translateText(
                        text,
                        sourceLanguage
                    )
                }

                if (listening) {
                    scheduleRestart(0)
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {}
        }
    }

    private fun scheduleRestart(error: Int) {

        if (!listening) return

        restartRunnable?.let {
            handler.removeCallbacks(it)
        }

        val delay = when (error) {
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> 3000L
            SpeechRecognizer.ERROR_NETWORK -> 4000L
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 4000L
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2500L
            SpeechRecognizer.ERROR_NO_MATCH -> 1200L
            else -> 2000L
        }

        val recreate =
            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

        restartRunnable = Runnable {
            if (listening) {
                startRecognition(recreate)
            }
        }

        handler.postDelayed(
            restartRunnable!!,
            delay
        )
    }

    private fun languageCode(tag: String): String {

        return when (
            tag.lowercase(Locale.ROOT).substringBefore("-")
        ) {
            "en" -> "en"
            "ar" -> "ar"
            "hi" -> "hi"
            "ur" -> "ur"
            "bn" -> "bn"
            else -> "en"
        }
    }

    private fun translateText(
        originalText: String,
        source: String
    ) {

        val requestId = translationId.incrementAndGet()

        // Bengali: Show directly without translation.

        if (source == "bn") {

            showPopup(
                "Detected: Bengali",
                originalText
            )

            updateNotification(
                "Bengali: $originalText"
            )

            return
        }

        updateNotification(
            "Translating: $originalText"
        )

        executor.execute {

            var translated = ""

            try {

                val encoded = URLEncoder.encode(
                    originalText,
                    "UTF-8"
                )

                val address =
                    "https://api.mymemory.translated.net/get?q=$encoded&langpair=$source%7Cbn"

                val connection =
                    URL(address).openConnection()
                        as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                try {

                    val response = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                    val json = JSONObject(response)

                    if (json.optInt("responseStatus") == 200) {

                        translated = json
                            .optJSONObject("responseData")
                            ?.optString("translatedText")
                            ?: ""
                    }

                } finally {
                    connection.disconnect()
                }

            } catch (e: Exception) {
                translated = ""
            }

            if (requestId == translationId.get()) {

                handler.post {

                    if (translated.isNotBlank()) {

                        showPopup(
                            "Detected: $source",
                            translated
                        )

                        updateNotification(
                            "Bengali translation ready"
                        )

                    } else {

                        updateNotification(
                            "Translation failed. Check internet."
                        )
                    }
                }
            }
        }
    }

    private fun showPopup(
        status: String,
        message: String
    ) {

        val popupIntent = Intent(
            this,
            FloatingTranslatorService::class.java
        ).apply {

            action =
                FloatingTranslatorService.ACTION_UPDATE

            putExtra(
                FloatingTranslatorService.EXTRA_STATUS,
                status
            )

            putExtra(
                FloatingTranslatorService.EXTRA_MESSAGE,
                message
            )
        }

        try {
            startService(popupIntent)
        } catch (e: Exception) {
            updateNotification("Popup update failed")
        }
    }

    private fun stopListening() {

        listening = false

        translationId.incrementAndGet()

        handler.removeCallbacksAndMessages(null)

        restartRunnable = null

        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Voice Translator",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "Automatic voice translation status"

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        message: String
    ): Notification {

        val stopIntent = Intent(
            this,
            VoiceForegroundService::class.java
        ).setAction(ACTION_STOP)

        val stopPendingIntent = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(
            this,
            MainActivity::class.java
        )

        val openPendingIntent = PendingIntent.getActivity(
            this,
            1003,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setContentTitle(
                "Live Voice Translator"
            )
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(message: String) {

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            createNotification(message)
        )
    }

    override fun onDestroy() {

        stopListening()

        executor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
