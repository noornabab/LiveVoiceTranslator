
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
import android.os.IBinder
import kotlin.concurrent.thread
import kotlin.math.sqrt

class VoiceForegroundService : Service() {

    companion object {
        const val ACTION_PREPARE = "PREPARE_VOICE_SERVICE"
        const val ACTION_START = "START_VOICE_LISTENING"
        const val ACTION_STOP_LISTENING = "STOP_VOICE_LISTENING"
        const val ACTION_STOP = "STOP_VOICE_SERVICE"

        private const val CHANNEL_ID = "live_voice_channel"
        private const val NOTIFICATION_ID = 1001
    }

    @Volatile
    private var recording = false

    private var foregroundStarted = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

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
                startForegroundNotification("Audio test ready")
            }

            ACTION_START -> {
                if (!foregroundStarted) {
                    startForegroundNotification("Starting audio test")
                }

                if (foregroundStarted) {
                    startAudioTest()
                }
            }

            ACTION_STOP_LISTENING -> {
                stopAudioTest()
                updateNotification("Audio test stopped")
            }

            ACTION_STOP -> {
                stopAudioTest()

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

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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

    private fun startForegroundNotification(message: String) {

        if (foregroundStarted) return

        try {

            val notification = createNotification(message)

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
            updateNotification("Service error: ${e.message}")
        }
    }

    private fun updateNotification(message: String) {

        if (!foregroundStarted) return

        try {

            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    createNotification(message)
                )

        } catch (_: Exception) {
        }
    }

    private fun startAudioTest() {

        stopAudioTest()

        val sampleRate = 16000

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            updateNotification("ERROR: Invalid audio buffer")
            return
        }

        try {

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                updateNotification("ERROR: Microphone initialization failed")
                return
            }

            audioRecord = recorder
            recorder.startRecording()

            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                stopAudioTest()
                updateNotification("ERROR: Recording did not start")
                return
            }

            recording = true

            updateNotification("AudioRecord active")

            recordingThread = thread(
                start = true,
                name = "AudioRecordTest"
            ) {

                val buffer = ShortArray(1024)
                var lastUpdate = 0L

                while (recording) {

                    val count = try {
                        recorder.read(
                            buffer,
                            0,
                            buffer.size
                        )
                    } catch (_: Exception) {
                        -1
                    }

                    if (count <= 0) {
                        updateNotification("ERROR: Audio read failed ($count)")
                        break
                    }

                    var sum = 0.0

                    for (i in 0 until count) {
                        val value = buffer[i].toDouble()
                        sum += value * value
                    }

                    val rms = sqrt(sum / count)

                    val level = (
                        20.0 * kotlin.math.log10(
                            (rms + 1.0) / 32768.0
                        ) + 100.0
                    ).toInt().coerceIn(0, 100)

                    val now = System.currentTimeMillis()

                    if (now - lastUpdate >= 700) {

                        lastUpdate = now

                        updateNotification(
                            "MIC AUDIO LEVEL: $level%"
                        )

                        updatePopup(
                            "মাইক্রোফোন অডিও পরীক্ষা চলছে...",
                            "AUDIO LEVEL: $level%"
                        )
                    }
                }

                if (recording) {
                    recording = false
                    updateNotification("Audio recording stopped")
                }
            }

        } catch (e: SecurityException) {

            updateNotification("MIC PERMISSION ERROR")

        } catch (e: Exception) {

            updateNotification("AUDIO ERROR: ${e.message}")
        }
    }

    private fun stopAudioTest() {

        recording = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
        recordingThread = null
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

    override fun onDestroy() {
        stopAudioTest()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
