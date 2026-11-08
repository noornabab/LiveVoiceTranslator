
package com.livevoicetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.log10
import kotlin.math.sqrt

class VoiceForegroundService : Service() {

    companion object {
        const val ACTION_PREPARE = "PREPARE_VOICE_SERVICE"
        const val ACTION_START = "START_VOICE_LISTENING"
        const val ACTION_STOP_LISTENING = "STOP_VOICE_LISTENING"
        const val ACTION_STOP = "STOP_VOICE_SERVICE"

        private const val CHANNEL_ID = "live_voice_channel"
        private const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = 16000
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var captureEnabled = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    private var audioThread: Thread? = null
    private var foregroundStarted = false

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
                    startAudioCapture()
                }
            }

            ACTION_STOP_LISTENING -> {

                stopAudioCapture()

                updateNotification("Microphone paused")
                updatePopupStatus("মাইক্রোফোন পরীক্ষা বন্ধ আছে")
            }

            ACTION_STOP -> {

                stopAudioCapture()

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
                createNotification("Microphone test ready")

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

            updateNotification("Foreground error: ${e.message}")

            updatePopupStatus(
                "Foreground service চালু হয়নি: ${e.message}"
            )
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

    private fun startAudioCapture() {

        stopAudioCapture()

        captureEnabled = true

        audioThread = Thread {

            var recorder: AudioRecord? = null

            try {

                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    throw Exception("Audio buffer তৈরি হয়নি")
                }

                val bufferSize = maxOf(
                    minBuffer,
                    SAMPLE_RATE * 2
                )

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw Exception("মাইক্রোফোন চালু হয়নি")
                }

                audioRecord = recorder

                recorder.startRecording()

                updateNotification("Microphone recording test")

                handler.post {
                    updatePopupStatus(
                        "🎙 মাইক্রোফোন পরীক্ষা চলছে..."
                    )
                }

                val buffer = ShortArray(bufferSize / 2)

                var lastUpdate = 0L

                while (captureEnabled) {

                    val readCount = recorder.read(
                        buffer,
                        0,
                        buffer.size
                    )

                    if (readCount > 0) {

                        var sum = 0.0

                        for (i in 0 until readCount) {

                            val sample = buffer[i].toDouble()

                            sum += sample * sample
                        }

                        val rms = sqrt(sum / readCount)

                        val db = if (rms > 0) {
                            20 * log10(rms / 32767.0)
                        } else {
                            -90.0
                        }

                        val level = (
                            ((db + 60.0) / 60.0) * 100.0
                        ).toInt().coerceIn(0, 100)

                        val hasSound = rms > 250

                        val currentTime =
                            System.currentTimeMillis()

                        if (currentTime - lastUpdate >= 700) {

                            lastUpdate = currentTime

                            val status = if (hasSound) {
                                "🟢 মাইক্রোফোন: শব্দ পাওয়া যাচ্ছে"
                            } else {
                                "🔴 মাইক্রোফোন: কোনো শব্দ পাওয়া যাচ্ছে না"
                            }

                            val message =
                                "$status\nAudio Level: $level%"

                            handler.post {

                                if (captureEnabled) {

                                    updatePopupStatus(message)

                                    updateNotification(
                                        "Audio Level: $level%"
                                    )
                                }
                            }
                        }
                    }

                    if (readCount < 0) {
                        throw Exception(
                            "AudioRecord error: $readCount"
                        )
                    }
                }

            } catch (e: SecurityException) {

                handler.post {
                    updatePopupStatus(
                        "⚠ Microphone permission নেই"
                    )
                }

            } catch (e: Exception) {

                handler.post {
                    updatePopupStatus(
                        "⚠ Audio error: ${e.message}"
                    )
                }

            } finally {

                try {
                    recorder?.release()
                } catch (_: Exception) {
                }

                if (audioRecord === recorder) {
                    audioRecord = null
                }
            }

        }.apply {
            name = "MicrophoneTestThread"
            start()
        }
    }

    private fun stopAudioCapture() {

        captureEnabled = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioThread?.join(500)
        } catch (_: Exception) {
        }

        audioThread = null
        audioRecord = null
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

        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {

        stopAudioCapture()

        handler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
