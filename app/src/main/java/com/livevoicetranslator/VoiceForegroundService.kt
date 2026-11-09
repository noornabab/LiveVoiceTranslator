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
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class VoiceForegroundService : Service() {

    companion object {
        const val ACTION_PREPARE = "PREPARE_VOICE_SERVICE"
        const val ACTION_START = "START_VOICE_LISTENING"
        const val ACTION_STOP_LISTENING = "STOP_VOICE_LISTENING"
        const val ACTION_STOP = "STOP_VOICE_SERVICE"

        private const val CHANNEL_ID = "live_voice_channel"
        private const val NOTIFICATION_ID = 1001

        private const val PREF_NAME = "LiveVoiceSettings"
        private const val PREF_LANGUAGE = "speech_language"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var listening = false

    private var foregroundStarted = false
    private var currentLanguage = "en-US"
    private var retryCount = 0

    private var lastAudioLevelUpdate = 0L

    private val restartRunnable = Runnable {
        if (listening) {
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

                if (foregroundStarted) {
                    startListening()
                }
            }

            ACTION_STOP_LISTENING -> {
                stopListening()

                updateNotification("Listening stopped")

                updatePopup(
                    "মাইক্রোফোন বন্ধ করা হয়েছে।",
                    "LISTENING STOPPED"
                )
            }

            ACTION_STOP -> {
                stopListening()

                if (foregroundStarted) {
                    stopForeground(true)
                    foregroundStarted = false
                }

                stopSelf()
            }
        }

        return START_NOT_STICKY
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

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }

    // =====================================
    // CREATE NOTIFICATION
    // =====================================

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

    // =====================================
    // START FOREGROUND
    // =====================================

    private fun startForegroundServiceNotification() {

        if (foregroundStarted) return

        try {

            val notification =
                createNotification("Voice translator ready")

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

        } catch (e: Exception) {

            updatePopup(
                "Service চালু হয়নি: ${e.message}",
                "SERVICE ERROR"
            )
        }
    }

    // =====================================
    // UPDATE NOTIFICATION
    // =====================================

    private fun updateNotification(message: String) {

        if (!foregroundStarted) return

        try {

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.notify(
                NOTIFICATION_ID,
                createNotification(message)
            )

        } catch (_: Exception) {
        }
    }

    // =====================================
    // LANGUAGE
    // =====================================

    private fun getSelectedLanguage(): String {

        val preferences =
            getSharedPreferences(
                PREF_NAME,
                MODE_PRIVATE
            )

        val selected =
            preferences.getString(
                PREF_LANGUAGE,
                "en-US"
            ) ?: "en-US"

        return when (selected) {
            "bn-BD" -> "bn-BD"
            "en-US" -> "en-US"
            "ar-SA" -> "ar-SA"
            "hi-IN" -> "hi-IN"
            else -> "en-US"
        }
    }

    private fun getTranslationLanguage(
        language: String
    ): String {

        return when {
            language.startsWith("en") -> "en"
            language.startsWith("ar") -> "ar"
            language.startsWith("hi") -> "hi"
            else -> "bn"
        }
    }

    // =====================================
    // START LISTENING
    // =====================================

    private fun startListening() {

        stopRecognizerOnly()

        handler.removeCallbacks(restartRunnable)

        listening = true
        retryCount = 0
        lastAudioLevelUpdate = 0L

        currentLanguage = getSelectedLanguage()

        updateNotification("Listening active")

        updatePopup(
            "মাইক্রোফোন প্রস্তুত হচ্ছে...",
            "🎙 LISTENING"
        )

        startRecognition()
    }

    // =====================================
    // CREATE RECOGNIZER
    // =====================================

    private fun createNewRecognizer(): Boolean {

        return try {

            stopRecognizerOnly()

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {

                updatePopup(
                    "Speech Recognition পাওয়া যাচ্ছে না।",
                    "RECOGNITION ERROR"
                )

                return false
            }

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(
                        params: Bundle?
                    ) {

                        if (!listening) return

                        updatePopup(
                            "কথা বলুন। কলের অডিও পরীক্ষা চলছে...",
                            "🎙 AUDIO LEVEL: অপেক্ষা..."
                        )
                    }

                    override fun onBeginningOfSpeech() {

                        if (!listening) return

                        updatePopup(
                            "আপনার কথা শোনা হচ্ছে...",
                            "🎙 SPEAKING"
                        )
                    }

                    // =====================================
                    // LIVE AUDIO LEVEL
                    // =====================================

                    override fun onRmsChanged(
                        rmsdB: Float
                    ) {

                        if (!listening) return

                        val now =
                            SystemClock.elapsedRealtime()

                        if (
                            now - lastAudioLevelUpdate < 500
                        ) {
                            return
                        }

                        lastAudioLevelUpdate = now

                        val safeDb =
                            if (rmsdB.isNaN() || rmsdB.isInfinite()) {
                                0f
                            } else {
                                rmsdB
                            }

                        val level =
                            ((safeDb + 2f) * 8f)
                                .toInt()
                                .coerceIn(0, 100)

                        updatePopup(
                            "কলের অডিও পরীক্ষা চলছে...",
                            "🎙 AUDIO LEVEL: $level%"
                        )
                    }

                    override fun onBufferReceived(
                        buffer: ByteArray?
                    ) {
                    }

                    override fun onEndOfSpeech() {

                        if (!listening) return

                        updatePopup(
                            "কথা শনাক্ত করা হচ্ছে...",
                            "PROCESSING"
                        )
                    }

                    // =====================================
                    // ERROR HANDLING
                    // =====================================

                    override fun onError(error: Int) {

                        if (!listening) return

                        val message = when (error) {

                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                                "Recognition সার্ভার বিচ্ছিন্ন। পুনরায় সংযোগ করছি..."

                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "কথা শনাক্ত হয়নি। আবার চেষ্টা করছি..."

                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "কোনো কথা পাওয়া যায়নি। আবার চেষ্টা করছি..."

                            SpeechRecognizer.ERROR_NETWORK ->
                                "ইন্টারনেট সংযোগ পরীক্ষা করুন।"

                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "ইন্টারনেটের উত্তর আসতে দেরি হচ্ছে।"

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "মাইক্রোফোন permission নেই।"

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Speech Recognition ব্যস্ত।"

                            else ->
                                "Speech Error: $error"
                        }

                        updatePopup(
                            message,
                            "RECONNECTING"
                        )

                        if (
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        ) {

                            retryCount++

                            scheduleRestart(
                                minOf(
                                    1500L * retryCount,
                                    6000L
                                ),
                                true
                            )

                        } else {

                            scheduleRestart(1500L, false)
                        }
                    }

                    // =====================================
                    // FINAL RESULTS
                    // =====================================

                    override fun onResults(
                        results: Bundle?
                    ) {

                        if (!listening) return

                        val matches =
                            results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val spokenText =
                            matches?.firstOrNull()
                                ?.trim()
                                .orEmpty()

                        if (spokenText.isNotBlank()) {

                            retryCount = 0

                            processRecognizedText(
                                spokenText,
                                currentLanguage
                            )

                        } else {

                            updatePopup(
                                "কথা শনাক্ত হয়নি। আবার বলুন...",
                                "LISTENING"
                            )
                        }

                        scheduleRestart(900L, false)
                    }

                    // =====================================
                    // PARTIAL RESULTS
                    // =====================================

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {

                        if (!listening) return

                        val matches =
                            partialResults?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val partialText =
                            matches?.firstOrNull()
                                ?.trim()
                                .orEmpty()

                        if (partialText.isNotBlank()) {

                            updatePopup(
                                "শোনা হচ্ছে:\n$partialText",
                                "🎙 LISTENING"
                            )
                        }
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {
                    }
                }
            )

            speechRecognizer != null

        } catch (e: Exception) {

            updatePopup(
                "Recognizer Error: ${e.message}",
                "ERROR"
            )

            false
        }
    }

    // =====================================
    // START RECOGNITION
    // =====================================

    private fun startRecognition() {

        if (!listening) return

        try {

            if (speechRecognizer == null) {

                if (!createNewRecognizer()) {
                    scheduleRestart(3000L, true)
                    return
                }
            }

            currentLanguage = getSelectedLanguage()

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    currentLanguage
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    currentLanguage
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

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {

            updatePopup(
                "Listening Error: ${e.message}",
                "RECONNECTING"
            )

            scheduleRestart(2000L, true)
        }
    }

    // =====================================
    // RESTART RECOGNITION
    // =====================================

    private fun scheduleRestart(
        delay: Long,
        recreate: Boolean
    ) {

        handler.removeCallbacks(restartRunnable)

        if (!listening) return

        if (recreate) {
            stopRecognizerOnly()
        }

        handler.postDelayed(
            restartRunnable,
            delay
        )
    }

    // =====================================
    // PROCESS SPEECH
    // =====================================

    private fun processRecognizedText(
        spokenText: String,
        language: String
    ) {

        if (!listening) return

        val sourceLanguage =
            getTranslationLanguage(language)

        if (
            sourceLanguage == "bn" ||
            containsBangla(spokenText)
        ) {

            updatePopup(
                spokenText,
                "বাংলা কথা শনাক্ত হয়েছে"
            )

            return
        }

        updatePopup(
            "মূল কথা:\n$spokenText\n\nবাংলায় অনুবাদ হচ্ছে...",
            "TRANSLATING"
        )

        Thread {

            val translatedText =
                translateToBangla(
                    spokenText,
                    sourceLanguage
                )

            handler.post {

                if (!listening) return@post

                if (translatedText.isNotBlank()) {

                    updatePopup(
                        "মূল কথা:\n$spokenText\n\nবাংলা অনুবাদ:\n$translatedText",
                        "বাংলা অনুবাদ সম্পন্ন"
                    )

                } else {

                    updatePopup(
                        "মূল কথা:\n$spokenText\n\nবাংলা অনুবাদ পাওয়া যায়নি।",
                        "TRANSLATION FAILED"
                    )
                }
            }

        }.apply {
            name = "TranslationThread"
            start()
        }
    }

    private fun containsBangla(text: String): Boolean {

        return text.any {
            it in '\u0980'..'\u09FF'
        }
    }

    // =====================================
    // TRANSLATION
    // =====================================

    private fun translateToBangla(
        text: String,
        sourceLanguage: String
    ): String {

        var connection: HttpURLConnection? = null

        return try {

            val encodedText =
                URLEncoder.encode(text, "UTF-8")

            val url = URL(
                "https://api.mymemory.translated.net/get" +
                    "?q=$encodedText" +
                    "&langpair=$sourceLanguage|bn"
            )

            connection =
                url.openConnection() as HttpURLConnection

            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.requestMethod = "GET"

            if (connection.responseCode != 200) {
                return ""
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            JSONObject(response)
                .getJSONObject("responseData")
                .optString("translatedText", "")
                .trim()

        } catch (_: Exception) {

            ""

        } finally {

            connection?.disconnect()
        }
    }

    // =====================================
    // STOP RECOGNIZER
    // =====================================

    private fun stopRecognizerOnly() {

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

    private fun stopListening() {

        listening = false

        handler.removeCallbacks(restartRunnable)

        stopRecognizerOnly()

        updateNotification("Listening paused")
    }

    // =====================================
    // POPUP UPDATE
    // =====================================

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

        } catch (_: Exception) {
        }
    }

    // =====================================
    // CLEANUP
    // =====================================

    override fun onDestroy() {

        stopListening()

        handler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
