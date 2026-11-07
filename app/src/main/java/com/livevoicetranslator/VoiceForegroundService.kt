
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

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val translationId = AtomicInteger(0)

    private var listening = false
    private var sourceLanguage = "en"
    private var lastPartialText = ""

    private val restartListening = Runnable {
        startRecognition()
    }

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
            val notification = createNotification(
                "Starting live translation..."
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                if (!listening) {
                    listening = true
                    startRecognition()
                }
            } else {
                updateNotification("Speech recognition unavailable")
            }

        } catch (e: Exception) {
            stopSelf()
        }

        return START_STICKY
    }

    private fun startRecognition() {
        if (!listening) return

        try {
            speechRecognizer?.destroy()

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        updateNotification(
                            "Listening: English / Hindi / Urdu / Arabic"
                        )
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        if (listening) {
                            handler.removeCallbacks(restartListening)
                            handler.postDelayed(restartListening, 1200)
                        }
                    }

                    override fun onLanguageDetection(results: Bundle) {
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

                        if (!text.isNullOrBlank() && text != lastPartialText) {
                            lastPartialText = text

                            handler.removeCallbacks(partialTranslation)
                            handler.postDelayed(
                                partialTranslation,
                                1000
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
                            translateText(text, sourceLanguage)
                        }

                        if (listening) {
                            handler.postDelayed(
                                restartListening,
                                500
                            )
                        }
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

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

                if (Build.VERSION.SDK_INT >= 34) {
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,
                        true
                    )

                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        arrayListOf(
                            "en-US",
                            "hi-IN",
                            "ur-PK",
                            "ar-SA"
                        )
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_BALANCED
                    )
                }
            }

            speechRecognizer?.startListening(speechIntent)

        } catch (e: Exception) {
            handler.postDelayed(restartListening, 1500)
        }
    }

    private fun languageCode(tag: String): String {
        return when (tag.lowercase(Locale.ROOT).substringBefore("-")) {
            "en" -> "en"
            "hi" -> "hi"
            "ur" -> "ur"
            "ar" -> "ar"
            else -> "en"
        }
    }

    private fun translateText(
        originalText: String,
        source: String
    ) {
        val requestId = translationId.incrementAndGet()

        updateNotification("Translating speech...")

        val updatePopup = Intent(
            this,
            FloatingTranslatorService::class.java
        ).apply {
            action = FloatingTranslatorService.ACTION_UPDATE
            putExtra(
                FloatingTranslatorService.EXTRA_STATUS,
                "Detected language: $source"
            )
            putExtra(
                FloatingTranslatorService.EXTRA_MESSAGE,
                "অনুবাদ হচ্ছে: $originalText"
            )
        }

        try {
            startService(updatePopup)
        } catch (_: Exception) {
        }

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
                    URL(address).openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val response = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val json = JSONObject(response)

                translated = json
                    .optJSONObject("responseData")
                    ?.optString("translatedText")
                    ?: ""

                connection.disconnect()

            } catch (_: Exception) {
                translated = ""
            }

            if (requestId == translationId.get()) {
                handler.post {
                    if (translated.isNotBlank()) {
                        updateNotification("Bengali translation ready")

                        val popupIntent = Intent(
                            this,
                            FloatingTranslatorService::class.java
                        ).apply {
                            action =
                                FloatingTranslatorService.ACTION_UPDATE

                            putExtra(
                                FloatingTranslatorService.EXTRA_STATUS,
                                "Original: $originalText"
                            )

                            putExtra(
                                FloatingTranslatorService.EXTRA_MESSAGE,
                                translated
                            )
                        }

                        try {
                            startService(popupIntent)
                        } catch (_: Exception) {
                        }

                    } else {
                        updateNotification("Translation failed. Check internet.")
                    }
                }
            }
        }
    }

    private fun stopListening() {
        listening = false

        handler.removeCallbacks(restartListening)
        handler.removeCallbacks(partialTranslation)

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

            channel.description = "Background live translation"

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
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

        val openIntent = Intent(this, MainActivity::class.java)

        val openPendingIntent = PendingIntent.getActivity(
            this,
            1003,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Live Voice Translator")
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
        getSystemService(NotificationManager::class.java)
            .notify(
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
