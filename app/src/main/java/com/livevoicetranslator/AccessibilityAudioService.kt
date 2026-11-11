
package com.livevoicetranslator

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AccessibilityAudioService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "LiveVoiceSettings"
        private const val PREF_LANGUAGE = "speech_language"

        private const val WORKER_URL =
            "https://live-voice-translator.support446-ff6.workers.dev"

        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        private const val CHUNK_SECONDS = 6
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null

    @Volatile
    private var callActive = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    private val recordingRunning = AtomicBoolean(false)

    private var windowManager: WindowManager? = null
    private var statusView: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val recordingExecutor =
        Executors.newSingleThreadExecutor()

    private val networkExecutor =
        Executors.newSingleThreadExecutor()

    @Volatile
    private var lastRecognizedText = ""

    // SERVICE CONNECTED

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        showStatusOverlay("Live Voice Translator প্রস্তুত")

        startPhoneMonitoring()
    }

    // PHONE MONITORING

    @Suppress("DEPRECATION")
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
                            lastRecognizedText = ""

                            showStatusOverlay("কল সংযুক্ত")
                            showTranslatorPopup()
                            startAudioCapture()
                        }

                        TelephonyManager.CALL_STATE_IDLE -> {
                            if (!callActive) return

                            callActive = false

                            stopAudioCapture()
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
                startAudioCapture()
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

    private fun getApiLanguage(): String {
        return when (getSelectedLanguage()) {
            "en-US", "en-IN", "en" -> "en"
            "ar-SA", "ar" -> "ar"
            "hi-IN", "hi" -> "hi"
            "bn-BD", "bn" -> "bn"
            else -> "bn"
        }
    }

    // START AUDIO CAPTURE

    private fun startAudioCapture() {
        if (!callActive) return

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus("MIC PERMISSION REQUIRED")
            return
        }

        if (!recordingRunning.compareAndSet(false, true)) {
            return
        }

        updateStatus("মাইক্রোফোন প্রস্তুত হচ্ছে...")

        recordingExecutor.execute {

            var recorder: AudioRecord? = null

            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    throw Exception("Invalid audio buffer: $minBuffer")
                }

                val bufferSize = maxOf(
                    minBuffer * 2,
                    SAMPLE_RATE * 2
                )

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (
                    recorder.state != AudioRecord.STATE_INITIALIZED
                ) {
                    throw Exception("AudioRecord initialization failed")
                }

                audioRecord = recorder

                recorder.startRecording()

                mainHandler.post {
                    if (callActive) {
                        updateStatus("অডিও রেকর্ডিং চলছে")
                    }
                }

                val chunkSize =
                    SAMPLE_RATE * CHANNELS *
                    (BITS_PER_SAMPLE / 8) *
                    CHUNK_SECONDS

                val readBuffer = ByteArray(8192)

                var audioChunk = ByteArrayOutputStream()

                while (
                    recordingRunning.get() &&
                    callActive
                ) {

                    val count = recorder.read(
                        readBuffer,
                        0,
                        readBuffer.size
                    )

                    if (count > 0) {
                        audioChunk.write(
                            readBuffer,
                            0,
                            count
                        )
                    } else if (count < 0) {
                        throw Exception("Audio read error: $count")
                    }

                    if (audioChunk.size() >= chunkSize) {

                        val pcm = audioChunk.toByteArray()

                        audioChunk = ByteArrayOutputStream()

                        if (
                            recordingRunning.get() &&
                            callActive
                        ) {
                            val wav = createWav(pcm)

                            sendAudioToWorker(wav)
                        }
                    }
                }

            } catch (e: Exception) {

                if (callActive) {
                    mainHandler.post {
                        updateStatus(
                            "AUDIO ERROR: ${e.message}"
                        )
                    }
                }

            } finally {

                try {
                    recorder?.stop()
                } catch (_: Exception) {
                }

                try {
                    recorder?.release()
                } catch (_: Exception) {
                }

                if (audioRecord === recorder) {
                    audioRecord = null
                }

                recordingRunning.set(false)
            }
        }
    }

    // CREATE WAV FILE

    private fun createWav(pcm: ByteArray): ByteArray {

        val output = ByteArrayOutputStream()

        val byteRate =
            SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        val totalDataLength = pcm.size + 36

        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(output, totalDataLength)

        output.write("WAVE".toByteArray(Charsets.US_ASCII))

        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(output, 16)
        writeShortLE(output, 1)
        writeShortLE(output, CHANNELS)
        writeIntLE(output, SAMPLE_RATE)
        writeIntLE(output, byteRate)
        writeShortLE(output, CHANNELS * BITS_PER_SAMPLE / 8)
        writeShortLE(output, BITS_PER_SAMPLE)

        output.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(output, pcm.size)

        output.write(pcm)

        return output.toByteArray()
    }

    private fun writeIntLE(
        output: ByteArrayOutputStream,
        value: Int
    ) {
        output.write(value and 0xff)
        output.write((value shr 8) and 0xff)
        output.write((value shr 16) and 0xff)
        output.write((value shr 24) and 0xff)
    }

    private fun writeShortLE(
        output: ByteArrayOutputStream,
        value: Int
    ) {
        output.write(value and 0xff)
        output.write((value shr 8) and 0xff)
    }

    // SEND AUDIO TO CLOUDFLARE WORKER

    private fun sendAudioToWorker(wav: ByteArray) {

        if (!callActive) return

        try {
            networkExecutor.execute {

                var connection: HttpURLConnection? = null

                try {
                    val language = getApiLanguage()

                    val url = URL(
                        "$WORKER_URL/transcribe?language=$language"
                    )

                    connection =
                        url.openConnection() as HttpURLConnection

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

                    connection.setFixedLengthStreamingMode(
                        wav.size
                    )

                    connection.outputStream.use {
                        it.write(wav)
                        it.flush()
                    }

                    val responseCode = connection.responseCode

                    val responseStream =
                        if (responseCode in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }

                    val responseText = responseStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: ""

                    if (responseCode != 200) {
                        throw Exception(
                            "Worker HTTP $responseCode: $responseText"
                        )
                    }

                    val result = JSONObject(responseText)

                    if (!result.optBoolean("success", false)) {
                        throw Exception(
                            result.optString(
                                "error",
                                "Recognition failed"
                            )
                        )
                    }

                    val original = result.optString(
                        "original",
                        ""
                    ).trim()

                    val bengali = result.optString(
                        "bengali",
                        ""
                    ).trim()

                    if (original.isBlank()) {
                        mainHandler.post {
                            if (callActive) {
                                updateStatus("কথা শনাক্ত হয়নি")
                            }
                        }
                        return@execute
                    }

                    if (original == lastRecognizedText) {
                        return@execute
                    }

                    lastRecognizedText = original

                    val message = if (
                        language == "bn"
                    ) {
                        "মূল কথা: $original\n\nবাংলা: $bengali"
                    } else {
                        "মূল কথা: $original\n\nবাংলা অনুবাদ: $bengali"
                    }

                    mainHandler.post {
                        if (!callActive) return@post

                        sendPopupUpdate(
                            message,
                            "কথা শনাক্ত ও অনুবাদ সম্পন্ন"
                        )
                    }

                } catch (e: Exception) {

                    mainHandler.post {
                        if (!callActive) return@post

                        updateStatus(
                            "WORKER ERROR: ${e.message}"
                        )
                    }

                } finally {
                    connection?.disconnect()
                }
            }

        } catch (e: Exception) {
            updateStatus("NETWORK QUEUE ERROR: ${e.message}")
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

                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,

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

    // STOP AUDIO CAPTURE

    private fun stopAudioCapture() {

        recordingRunning.set(false)

        try {
            audioRecord?.let { recorder ->
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        audioRecord = null
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
        stopAudioCapture()
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {

        callActive = false

        stopAudioCapture()
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

        recordingExecutor.shutdownNow()
        networkExecutor.shutdownNow()

        super.onDestroy()
    }
}
