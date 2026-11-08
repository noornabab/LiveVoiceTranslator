
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

        const val ACTION_PREPARE = "PREPARE_VOICE_SERVICE"
        const val ACTION_START = "START_VOICE_SERVICE"
        const val ACTION_STOP_LISTENING = "STOP_CALL_LISTENING"
        const val ACTION_STOP = "STOP_VOICE_SERVICE"

        private const val CHANNEL_ID =
            "voice_translator_channel"

        private const val NOTIFICATION_ID = 1001
    }

    private val handler =
        Handler(Looper.getMainLooper())

    private val executor =
        Executors.newSingleThreadExecutor()

    private val translationId =
        AtomicInteger(0)

    private var speechRecognizer: SpeechRecognizer? = null

    private var listening = false
    private var foregroundStarted = false

    private var sourceLanguage = "en"

    private var restartRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // =====================================
    // SERVICE COMMANDS
    // =====================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {

                stopListening()

                if (foregroundStarted) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foregroundStarted = false
                }

                stopSelf()

                return START_NOT_STICKY
            }

            ACTION_STOP_LISTENING -> {

                stopListening()

                updateNotification(
                    "কল নেই। Listening বন্ধ।"
                )

                return START_NOT_STICKY
            }

            ACTION_PREPARE -> {

                if (!ensureForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                updateNotification(
                    "প্রস্তুত। কলের অপেক্ষায়..."
                )

                return START_STICKY
            }

            ACTION_START -> {

                if (!ensureForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (
                    checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    updateNotification(
                        "Microphone permission প্রয়োজন"
                    )
                    return START_NOT_STICKY
                }

                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    updateNotification(
                        "Speech Recognition unavailable"
                    )
                    return START_NOT_STICKY
                }

                if (!listening) {
                    listening = true
                    sourceLanguage = "en"
                    startRecognition(true)
                }

                return START_STICKY
            }

            else -> {

                if (!ensureForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                return START_STICKY
            }
        }
    }

    // =====================================
    // FOREGROUND SERVICE
    // =====================================

    private fun ensureForeground(): Boolean {

        if (foregroundStarted) {
            return true
        }

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {

            val notification =
                createNotification(
                    "প্রস্তুত। কলের অপেক্ষায়..."
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

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

            true

        } catch (e: Exception) {

            updateNotification(
                "Foreground Error: ${e.message}"
            )

            false
        }
    }

    // =====================================
    // SPEECH RECOGNITION
    // =====================================

    private fun startRecognition(
        recreate: Boolean = false
    ) {

        if (!listening) return

        try {

            if (
                recreate ||
                speechRecognizer == null
            ) {

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
                "কল চলাকালীন Listening চালু"
            )

            speechRecognizer?.startListening(
                speechIntent
            )

        } catch (e: Exception) {

            updateNotification(
                "Recognition Error: ${e.message}"
            )

            scheduleRestart(
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED
            )
        }
    }

    // =====================================
    // RECOGNITION LISTENER
    // =====================================

    private fun createRecognitionListener(): RecognitionListener {

        return object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {

                updateNotification(
                    "Listening: 5 Languages"
                )
            }

            override fun onBeginningOfSpeech() {

                updateNotification(
                    "কথা শনাক্ত হচ্ছে..."
                )
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {

                updateNotification(
                    "কথা প্রসেস হচ্ছে..."
                )
            }

            override fun onError(error: Int) {

                if (!listening) return

                updateNotification(
                    when (error) {

                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "কথা শনাক্ত হয়নি। আবার চেষ্টা করছি..."

                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                            "Speech Server disconnected"

                        SpeechRecognizer.ERROR_NETWORK ->
                            "Network Error"

                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Network Timeout"

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission নেই"

                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "Recognizer busy"

                        else ->
                            "Speech Error Code: $error"
                    }
                )

                if (
                    error ==
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                ) {
                    stopListening()
                    return
                }

                scheduleRestart(error)
            }

            override fun onLanguageDetection(
                results: Bundle
            ) {

                if (Build.VERSION.SDK_INT >= 34) {

                    val detected = results.getString(
                        SpeechRecognizer.DETECTED_LANGUAGE
                    )

                    if (!detected.isNullOrBlank()) {

                        sourceLanguage =
                            languageCode(detected)

                        updateNotification(
                            "Detected: $detected"
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

                    updateNotification(
                        "Heard: $text"
                    )
                }
            }

            override fun onResults(results: Bundle?) {

                if (!listening) return

                val text = results
                    ?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    ?.firstOrNull()

                if (!text.isNullOrBlank()) {

                    translateText(
                        text,
                        sourceLanguage
                    )
                }

                scheduleRestart(0)
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {}
        }
    }

    // =====================================
    // RESTART RECOGNITION
    // =====================================

    private fun scheduleRestart(error: Int) {

        if (!listening) return

        restartRunnable?.let {
            handler.removeCallbacks(it)
        }

        val delay = when (error) {

            SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                3000L

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                4000L

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                2500L

            SpeechRecognizer.ERROR_NO_MATCH ->
                1200L

            else ->
                2000L
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

    // =====================================
    // LANGUAGE DETECTION
    // =====================================

    private fun languageCode(tag: String): String {

        return when (
            tag.lowercase(Locale.ROOT)
                .substringBefore("-")
        ) {

            "en" -> "en"
            "ar" -> "ar"
            "hi" -> "hi"
            "ur" -> "ur"
            "bn" -> "bn"

            else -> "en"
        }
    }

    // =====================================
    // TRANSLATION
    // =====================================

    private fun translateText(
        originalText: String,
        source: String
    ) {

        val requestId =
            translationId.incrementAndGet()

        // Bengali: show original text directly.

        if (source == "bn") {

            showPopup(
                "Detected: Bengali",
                originalText
            )

            updateNotification(
                "বাংলা: $originalText"
            )

            return
        }

        updateNotification(
            "Translating to Bengali..."
        )

        executor.execute {

            var translated = ""

            try {

                val encoded = URLEncoder.encode(
                    originalText,
                    "UTF-8"
                )

                val address =
                    "https://api.mymemory.translated.net/get" +
                    "?q=$encoded&langpair=$source%7Cbn"

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

                    if (
                        json.optInt("responseStatus") == 200
                    ) {

                        translated = json
                            .optJSONObject("responseData")
                            ?.optString("translatedText")
                            ?: ""
                    }

                } finally {

                    connection.disconnect()
                }

            } catch (_: Exception) {

                translated = ""
            }

            if (requestId == translationId.get()) {

                handler.post {

                    if (!listening) return@post

                    if (translated.isNotBlank()) {

                        showPopup(
                            "Detected: $source",
                            translated
                        )

                        updateNotification(
                            "বাংলা অনুবাদ প্রস্তুত"
                        )

                    } else {

                        updateNotification(
                            "অনুবাদ পাওয়া যায়নি। Internet পরীক্ষা করুন।"
                        )
                    }
                }
            }
        }
    }

    // =====================================
    // UPDATE FLOATING POPUP
    // =====================================

    private fun showPopup(
        status: String,
        message: String
    ) {

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

            putExtra(
                FloatingTranslatorService.EXTRA_MESSAGE,
                message
            )
        }

        try {

            startService(intent)

        } catch (_: Exception) {

            updateNotification(
                "Popup update failed"
            )
        }
    }

    // =====================================
    // STOP MICROPHONE
    // =====================================

    private fun stopListening() {

        listening = false

        translationId.incrementAndGet()

        handler.removeCallbacksAndMessages(null)

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

    // =====================================
    // NOTIFICATION CHANNEL
    // =====================================

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Voice Translator",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "Call voice translation status"

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    // =====================================
    // NOTIFICATION
    // =====================================

    private fun createNotification(
        message: String
    ): Notification {

        val stopIntent = Intent(
            this,
            VoiceForegroundService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent =
            PendingIntent.getService(
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

        val openPendingIntent =
            PendingIntent.getActivity(
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
                "STOP SERVICE",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(
        message: String
    ) {

        if (!foregroundStarted) return

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            createNotification(message)
        )
    }

    // =====================================
    // CLEANUP
    // =====================================

    override fun onDestroy() {

        stopListening()

        executor.shutdownNow()

        foregroundStarted = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
