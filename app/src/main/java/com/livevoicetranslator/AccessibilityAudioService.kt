
package com.livevoicetranslator

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AccessibilityAudioService : AccessibilityService() {

    companion object {
        private const val WORKER_URL =
            "https://live-voice-translator.support446-ff6.workers.dev"

        private const val PREFS_NAME = "LiveVoiceSettings"
        private const val PREF_LANGUAGE = "speech_language"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG =
            AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        private const val READ_SIZE = 2048

        // RMS threshold: quiet speech may need adjustment
        private const val SPEECH_THRESHOLD = 180.0

        // Silence duration before final submission
        private const val SILENCE_DURATION_MS = 1200L

        // Preview interval
        private const val PREVIEW_INTERVAL_MS = 3500L

        // Maximum segment size (before forced finalization)
        private const val MAX_SEGMENT_BYTES = 1_300_000

        // Ignore very short audio
        private const val MIN_FINAL_BYTES = 12000

        private const val PRE_ROLL_CHUNKS = 8
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val networkExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val previewInFlight = AtomicBoolean(false)

    @Volatile
    private var callActive = false

    @Volatile
    private var captureRunning = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    private var recordingThread: Thread? = null

    private var telephonyManager: TelephonyManager? = null

    private var lastFinalText = ""

    private var currentLanguage = "en"

    private val phoneListener = object : PhoneStateListener() {

        override fun onCallStateChanged(
            state: Int,
            incomingNumber: String?
        ) {
            super.onCallStateChanged(state, incomingNumber)

            when (state) {

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (!callActive) {
                        callActive = true
                        lastFinalText = ""

                        currentLanguage = getApiLanguage()

                        showPopup()

                        updateStatus(
                            "কল সংযুক্ত হয়েছে। কথা বলুন..."
                        )

                        startAudioCapture()
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    if (callActive) {
                        callActive = false

                        updateStatus(
                            "কল শেষ হয়েছে। শেষ কথাটি প্রসেস হচ্ছে..."
                        )

                        stopAudioCapture()

                        // Allow final audio request time to finish
                        mainHandler.postDelayed({
                            hidePopup()
                        }, 6000)
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    // Wait until the call is answered
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        if (
            checkSelfPermission(
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus(
                "Phone permission প্রয়োজন"
            )
            return
        }

        telephonyManager =
            getSystemService(Context.TELEPHONY_SERVICE)
                    as? TelephonyManager

        try {
            telephonyManager?.listen(
                phoneListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )
        } catch (e: Exception) {
            updateStatus(
                "Call monitoring error: ${e.message}"
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not required
    }

    override fun onInterrupt() {
        stopAudioCapture()
    }

    override fun onDestroy() {
        callActive = false

        try {
            telephonyManager?.listen(
                phoneListener,
                PhoneStateListener.LISTEN_NONE
            )
        } catch (_: Exception) {
        }

        stopAudioCapture()

        networkExecutor.shutdown()

        super.onDestroy()
    }

    // =====================================
    // LANGUAGE SELECTION
    // =====================================

    private fun getApiLanguage(): String {

        val preferences = getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val selected = preferences.getString(
            PREF_LANGUAGE,
            "bn-BD"
        ) ?: "bn-BD"

        return when {
            selected.startsWith("bn", true) -> "bn"
            selected.startsWith("en", true) -> "en"
            selected.startsWith("ar", true) -> "ar"
            selected.startsWith("hi", true) -> "hi"
            else -> "bn"
        }
    }

    // =====================================
    // POPUP CONTROL
    // =====================================

    private fun showPopup() {

        val intent = Intent(
            this,
            FloatingTranslatorService::class.java
        ).apply {
            action = FloatingTranslatorService.ACTION_SHOW
        }

        try {
            startService(intent)
        } catch (e: Exception) {
            updateStatus(
                "Popup error: ${e.message}"
            )
        }
    }

    private fun hidePopup() {

        val intent = Intent(
            this,
            FloatingTranslatorService::class.java
        ).apply {
            action = FloatingTranslatorService.ACTION_HIDE
        }

        try {
            startService(intent)
        } catch (_: Exception) {
        }
    }

    private fun sendPopupUpdate(
        message: String,
        status: String
    ) {

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

        try {
            startService(intent)
        } catch (_: Exception) {
        }
    }

    private fun updateStatus(message: String) {
        sendPopupUpdate(message, "")
    }

    // =====================================
    // AUDIO CAPTURE START
    // =====================================

    private fun startAudioCapture() {

        if (captureRunning) return

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus(
                "Microphone permission প্রয়োজন"
            )
            return
        }

        captureRunning = true

        recordingThread = Thread {

            captureAudioLoop()

        }.apply {
            name = "LiveTranslatorAudio"
            start()
        }
    }

    // =====================================
    // AUDIO CAPTURE LOOP
    // =====================================

    private fun captureAudioLoop() {

        var recorder: AudioRecord? = null

        val segment = ByteArrayOutputStream()

        val preRoll = ArrayDeque<ByteArray>()

        var inSpeech = false

        var lastSpeechAt = 0L

        var lastPreviewAt = 0L

        try {

            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minimumBuffer <= 0) {
                updateStatus(
                    "AudioRecord buffer error: $minimumBuffer"
                )
                return
            }

            val bufferSize = maxOf(
                minimumBuffer * 2,
                READ_SIZE * 4
            )

            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (
                recorder.state != AudioRecord.STATE_INITIALIZED
            ) {
                updateStatus(
                    "Microphone initialize failed"
                )
                return
            }

            audioRecord = recorder

            recorder.startRecording()

            updateStatus(
                "মাইক্রোফোন চালু। কথা শুনছি..."
            )

            val samples = ShortArray(READ_SIZE)

            while (captureRunning) {

                val readCount = recorder.read(
                    samples,
                    0,
                    samples.size
                )

                if (readCount <= 0) {
                    if (!captureRunning) break
                    continue
                }

                val chunkBytes = shortsToBytes(
                    samples,
                    readCount
                )

                val rms = calculateRms(
                    samples,
                    readCount
                )

                val now = System.currentTimeMillis()

                val speechDetected =
                    rms >= SPEECH_THRESHOLD

                if (speechDetected) {

                    if (!inSpeech) {

                        inSpeech = true

                        segment.reset()

                        // Include a little audio before speech starts
                        for (previous in preRoll) {
                            segment.write(previous)
                        }

                        preRoll.clear()

                        lastSpeechAt = now
                        lastPreviewAt = now
                    }

                    segment.write(chunkBytes)

                    lastSpeechAt = now

                } else {

                    if (inSpeech) {

                        // Keep silence at the end of the segment
                        segment.write(chunkBytes)

                    } else {

                        preRoll.addLast(chunkBytes)

                        while (
                            preRoll.size > PRE_ROLL_CHUNKS
                        ) {
                            preRoll.removeFirst()
                        }
                    }
                }

                // =================================
                // LIVE PREVIEW
                // =================================

                if (
                    inSpeech &&
                    now - lastPreviewAt >= PREVIEW_INTERVAL_MS &&
                    segment.size() > 30000
                ) {

                    val audioSnapshot = segment.toByteArray()

                    sendAudioToWorker(
                        audioSnapshot,
                        "preview",
                        currentLanguage
                    )

                    lastPreviewAt = now
                }

                // =================================
                // FINAL AFTER SILENCE
                // =================================

                if (
                    inSpeech &&
                    now - lastSpeechAt >= SILENCE_DURATION_MS
                ) {

                    val finalAudio = segment.toByteArray()

                    if (finalAudio.size >= MIN_FINAL_BYTES) {

                        sendAudioToWorker(
                            finalAudio,
                            "final",
                            currentLanguage
                        )
                    }

                    segment.reset()

                    inSpeech = false

                    lastSpeechAt = 0L
                    lastPreviewAt = 0L
                }

                // =================================
                // MAXIMUM SEGMENT SIZE
                // =================================

                if (
                    inSpeech &&
                    segment.size() >= MAX_SEGMENT_BYTES
                ) {

                    val finalAudio = segment.toByteArray()

                    sendAudioToWorker(
                        finalAudio,
                        "final",
                        currentLanguage
                    )

                    segment.reset()

                    lastSpeechAt = now
                    lastPreviewAt = now
                }
            }

        } catch (e: Exception) {

            if (callActive) {
                updateStatus(
                    "Audio error: ${e.message}"
                )
            }

        } finally {

            // =================================
            // SEND REMAINING AUDIO
            // =================================

            try {

                val remainingAudio = segment.toByteArray()

                if (
                    inSpeech &&
                    remainingAudio.size >= MIN_FINAL_BYTES
                ) {

                    sendAudioToWorker(
                        remainingAudio,
                        "final",
                        currentLanguage
                    )
                }

            } catch (_: Exception) {
            }

            try {
                recorder?.stop()
            } catch (_: Exception) {
            }

            try {
                recorder?.release()
            } catch (_: Exception) {
            }

            audioRecord = null
        }
    }

    // =====================================
    // STOP AUDIO CAPTURE
    // =====================================

    private fun stopAudioCapture() {

        captureRunning = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    // =====================================
    // RMS CALCULATION
    // =====================================

    private fun calculateRms(
        samples: ShortArray,
        count: Int
    ): Double {

        if (count <= 0) return 0.0

        var sum = 0.0

        for (i in 0 until count) {

            val value = samples[i].toDouble()

            sum += value * value
        }

        return sqrt(sum / count)
    }

    // =====================================
    // SHORT ARRAY TO PCM BYTES
    // =====================================

    private fun shortsToBytes(
        samples: ShortArray,
        count: Int
    ): ByteArray {

        val bytes = ByteArray(count * 2)

        for (i in 0 until count) {

            val value = samples[i].toInt()

            bytes[i * 2] =
                (value and 0xff).toByte()

            bytes[i * 2 + 1] =
                ((value shr 8) and 0xff).toByte()
        }

        return bytes
    }

    // =====================================
    // CREATE WAV FILE
    // =====================================

    private fun createWav(
        pcmData: ByteArray
    ): ByteArray {

        val output = ByteArrayOutputStream()

        fun writeText(text: String) {
            output.write(
                text.toByteArray(Charsets.US_ASCII)
            )
        }

        fun writeInt(value: Int) {

            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
            output.write((value shr 16) and 0xff)
            output.write((value shr 24) and 0xff)
        }

        fun writeShort(value: Int) {

            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
        }

        writeText("RIFF")
        writeInt(36 + pcmData.size)

        writeText("WAVE")
        writeText("fmt ")

        writeInt(16)
        writeShort(1)
        writeShort(1)

        writeInt(SAMPLE_RATE)
        writeInt(SAMPLE_RATE * 2)

        writeShort(2)
        writeShort(16)

        writeText("data")
        writeInt(pcmData.size)

        output.write(pcmData)

        return output.toByteArray()
    }

    // =====================================
    // SEND AUDIO TO CLOUDFLARE WORKER
    // =====================================

    private fun sendAudioToWorker(
        pcmData: ByteArray,
        mode: String,
        language: String
    ) {

        if (pcmData.isEmpty()) return

        // Do not queue multiple previews
        if (mode == "preview") {

            if (
                !previewInFlight.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }
        }

        networkExecutor.execute {

            try {

                val wavData = createWav(pcmData)

                val endpoint =
                    "$WORKER_URL/transcribe" +
                    "?language=$language" +
                    "&mode=$mode"

                val connection =
                    URL(endpoint).openConnection()
                            as HttpURLConnection

                try {

                    connection.requestMethod = "POST"

                    connection.connectTimeout = 20000
                    connection.readTimeout = 60000

                    connection.doOutput = true

                    connection.setRequestProperty(
                        "Content-Type",
                        "audio/wav"
                    )

                    connection.setRequestProperty(
                        "Accept",
                        "application/json"
                    )

                    connection.outputStream.use {
                        it.write(wavData)
                    }

                    val responseCode =
                        connection.responseCode

                    val responseStream =
                        if (responseCode in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }

                    val responseText =
                        responseStream?.bufferedReader()
                            ?.use { it.readText() }
                            ?: ""

                    if (responseCode !in 200..299) {

                        val errorMessage = try {
                            JSONObject(responseText)
                                .optString(
                                    "error",
                                    "Server error $responseCode"
                                )
                        } catch (_: Exception) {
                            "Server error $responseCode"
                        }

                        if (mode == "final") {
                            sendPopupUpdate(
                                errorMessage,
                                "TRANSLATION FAILED"
                            )
                        }

                        return@use
                    }

                    val result = JSONObject(responseText)

                    if (!result.optBoolean("success", false)) {

                        if (mode == "final") {
                            sendPopupUpdate(
                                result.optString(
                                    "error",
                                    "Translation failed"
                                ),
                                "TRANSLATION FAILED"
                            )
                        }

                        return@use
                    }

                    val original =
                        result.optString("original", "").trim()

                    val bengali =
                        result.optString("bengali", "").trim()

                    if (
                        original.isEmpty() ||
                        bengali.isEmpty()
                    ) {
                        return@use
                    }

                    val message = if (language == "bn") {

                        "মূল কথা: $original\n\nবাংলা: $bengali"

                    } else {

                        "মূল কথা: $original\n\nবাংলা অনুবাদ: $bengali"
                    }

                    if (mode == "preview") {

                        sendPopupUpdate(
                            message,
                            "লাইভ লেখা আপডেট হচ্ছে"
                        )

                    } else {

                        if (message != lastFinalText) {

                            lastFinalText = message

                            val translationError =
                                result.optString(
                                    "translationError",
                                    ""
                                )

                            val cleanupError =
                                result.optString(
                                    "cleanupError",
                                    ""
                                )

                            val status =
                                if (
                                    translationError.isNotEmpty() ||
                                    cleanupError.isNotEmpty()
                                ) {
                                    "TRANSLATION FAILED"
                                } else {
                                    "কথা শনাক্ত ও অনুবাদ সম্পন্ন"
                                }

                            sendPopupUpdate(
                                message,
                                status
                            )
                        }
                    }

                } finally {

                    connection.disconnect()
                }

            } catch (e: Exception) {

                if (mode == "final") {

                    sendPopupUpdate(
                        "সার্ভার সংযোগ সমস্যা: ${e.message}",
                        "TRANSLATION FAILED"
                    )
                }

            } finally {

                if (mode == "preview") {
                    previewInFlight.set(false)
                }
            }
        }
    }
}
