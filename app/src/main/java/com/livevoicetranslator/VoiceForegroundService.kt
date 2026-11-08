
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

    private var currentLanguage = "bn-BD"

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
    // NOTIFICATION
    // =====================================

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Voice Translator",
                NotificationManager.IMPORTANCE_LOW
            )

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

        try {

            val notification =
                createNotification("Voice translator ready")

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

            updatePopup(
                "Service চালু হয়নি: ${e.message}",
                "SERVICE ERROR"
            )
        }
    }

    private fun updateNotification(message: String) {

        if (!foregroundStarted) return

        try {

            val manager =
                getSystemService(NOTIFICATION_SERVICE)
                    as NotificationManager

            manager.notify(
                NOTIFICATION_ID,
                createNotification(message)
            )

        } catch (_: Exception) {
        }
    }

    // =====================================
    // LANGUAGE SELECTION
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
                "bn-BD"
            ) ?: "bn-BD"

        return when (selected) {

            "bn-BD" -> "bn-BD"
            "en-US" -> "en-US"
            "ar-SA" -> "ar-SA"
            "hi-IN" -> "hi-IN"

            else -> "bn-BD"
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

        if (listening) {
            stopRecognizerOnly()
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {

            updatePopup(
                "এই ফোনে Speech Recognition পাওয়া যাচ্ছে না।",
                "SPEECH RECOGNITION ERROR"
            )

            return
        }

        listening = true

        updateNotification("Listening active")

        updatePopup(
            "কথা শুনছি...",
            "🎙 LISTENING"
        )

        startRecognition()
    }

    // =====================================
    // SPEECH RECOGNITION
    // =====================================

    private fun startRecognition() {

        if (!listening) return

        try {

            currentLanguage = getSelectedLanguage()

            if (speechRecognizer == null) {

                speechRecognizer =
                    SpeechRecognizer.createSpeechRecognizer(this)

                speechRecognizer?.setRecognitionListener(
                    object : RecognitionListener {

                        override fun onReadyForSpeech(
                            params: Bundle?
                        ) {

                            updatePopup(
                                "কথা বলুন...",
                                "🎙 মাইক্রোফোন প্রস্তুত"
                            )
                        }

                        override fun onBeginningOfSpeech() {

                            updatePopup(
                                "আপনার কথা শোনা হচ্ছে...",
                                "🎙 SPEAKING"
                            )
                        }

                        override fun onRmsChanged(
                            rmsdB: Float
                        ) {
                            // Audio input level callback
                        }

                        override fun onBufferReceived(
                            buffer: ByteArray?
                        ) {
                        }

                        override fun onEndOfSpeech() {

                            updatePopup(
                                "কথা শনাক্ত করা হচ্ছে...",
                                "PROCESSING"
                            )
                        }

                        override fun onError(error: Int) {

                            if (!listening) return

                            val message = when (error) {

                                SpeechRecognizer.ERROR_NO_MATCH ->
                                    "কথা শনাক্ত হয়নি। আবার চেষ্টা করছি..."

                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    "কোনো কথা পাওয়া যায়নি। আবার বলুন..."

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
                                "LISTENING"
                            )

                            scheduleRestart(
                                if (
                                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                                ) 1500 else 900
                            )
                        }

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

                            scheduleRestart(900)
                        }

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
                "ERROR"
            )

            scheduleRestart(1500)
        }
    }

    private fun scheduleRestart(delay: Long) {

        handler.removeCallbacks(restartRunnable)

        if (listening) {
            handler.postDelayed(
                restartRunnable,
                delay
            )
        }
    }

    // =====================================
    // PROCESS RECOGNIZED TEXT
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
            "শোনা হয়েছে:\n$spokenText\n\nবাংলায় অনুবাদ হচ্ছে...",
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
    // TRANSLATION API
    // =====================================

    private fun translateToBangla(
        text: String,
        sourceLanguage: String
    ): String {

        var connection: HttpURLConnection? = null

        return try {

            val encodedText =
                URLEncoder.encode(
                    text,
                    "UTF-8"
                )

            val languagePair =
                "$sourceLanguage|bn"

            val url = URL(
                "https://api.mymemory.translated.net/get" +
                    "?q=$encodedText" +
                    "&langpair=$languagePair"
            )

            connection =
                url.openConnection() as HttpURLConnection

            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.requestMethod = "GET"

            val responseCode =
                connection.responseCode

            if (responseCode != 200) {
                return ""
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val json =
                JSONObject(response)

            json.getJSONObject("responseData")
                .optString("translatedText", "")
                .trim()

        } catch (_: Exception) {

            ""

        } finally {

            connection?.disconnect()
        }
    }

    // =====================================
    // STOP RECOGNITION
    // =====================================

    private fun stopRecognizerOnly() {

        handler.removeCallbacks(restartRunnable)

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
    // UPDATE FLOATING POPUP
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
