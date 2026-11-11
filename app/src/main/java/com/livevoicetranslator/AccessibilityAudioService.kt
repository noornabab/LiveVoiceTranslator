
package com.livevoicetranslator

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AccessibilityAudioService : AccessibilityService() {

    private var telephonyManager: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null

    @Volatile
    private var recording = false

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private var windowManager: WindowManager? = null
    private var statusView: TextView? = null

    private val mainHandler by lazy {
        Handler(mainLooper)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        showStatusOverlay("কলের অডিও পরীক্ষার জন্য প্রস্তুত")

        startPhoneMonitoring()
    }

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
                            showStatusOverlay("কল সংযুক্ত — অডিও পরীক্ষা")

                            // FIX: কল শুরু হলে Translator Popup খুলবে
                            showTranslatorPopup()

                            startAudioCapture()
                        }

                        TelephonyManager.CALL_STATE_IDLE -> {
                            stopAudioCapture()

                            // FIX: কল শেষ হলে Translator Popup বন্ধ হবে
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

            // Service চালু হওয়ার সময় কল আগে থেকেই চললে
            if (
                telephonyManager?.callState ==
                TelephonyManager.CALL_STATE_OFFHOOK
            ) {
                showTranslatorPopup()
                startAudioCapture()
            }

        } catch (e: Exception) {
            updateStatus("PHONE ERROR: ${e.message}")
        }
    }

    // =====================================
    // TRANSLATOR POPUP CONTROL
    // =====================================

    private fun showTranslatorPopup() {
        sendPopupAction(FloatingTranslatorService.ACTION_SHOW)
    }

    private fun hideTranslatorPopup() {
        sendPopupAction(FloatingTranslatorService.ACTION_HIDE)
    }

    private fun sendPopupAction(action: String) {
        try {
            val intent = Intent(
                this,
                FloatingTranslatorService::class.java
            ).apply {
                this.action = action
            }

            startService(intent)

        } catch (e: Exception) {
            mainHandler.post {
                statusView?.text =
                    "POPUP SERVICE ERROR: ${e.message}"
            }
        }
    }

    // =====================================
    // AUDIO CAPTURE
    // =====================================

    private fun startAudioCapture() {
        if (recording) return

        stopAudioCapture()

        val sampleRate = 48000

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize <= 0) {
            updateStatus("ERROR: AUDIO BUFFER $bufferSize")
            return
        }

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                updateStatus("ERROR: AUDIO INITIALIZATION")
                return
            }

            audioRecord = recorder
            recorder.startRecording()

            if (
                recorder.recordingState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                recorder.release()
                audioRecord = null
                updateStatus("ERROR: RECORDING NOT STARTED")
                return
            }

            recording = true
            updateStatus("VOICE_RECOGNITION STARTED")

            recordingThread = thread(
                start = true,
                name = "AccessibilityAudioCapture"
            ) {
                val buffer = ShortArray(2048)
                var lastUpdate = 0L
                var packetCount = 0L

                while (recording) {

                    val count = try {
                        recorder.read(
                            buffer,
                            0,
                            buffer.size,
                            AudioRecord.READ_BLOCKING
                        )
                    } catch (_: Exception) {
                        -1
                    }

                    if (!recording) break

                    if (count <= 0) {
                        updateStatus("AUDIO READ ERROR: $count")
                        break
                    }

                    packetCount++

                    var sum = 0.0
                    var peak = 0
                    var nonZero = 0

                    for (i in 0 until count) {
                        val value = buffer[i].toInt()
                        val absolute = kotlin.math.abs(value)

                        if (absolute > peak) peak = absolute
                        if (value != 0) nonZero++

                        sum += value.toDouble() * value.toDouble()
                    }

                    val rms = sqrt(sum / count)
                    val now = System.currentTimeMillis()

                    if (now - lastUpdate >= 700) {
                        lastUpdate = now

                        val result = if (nonZero == 0) {
                            "ZERO PCM — কোনো অডিও আসছে না"
                        } else {
                            "AUDIO ACTIVE | RMS: ${rms.toInt()} | Pack: $packetCount"
                        }

                        updateStatus(result)
                    }
                }
            }

        } catch (e: SecurityException) {
            updateStatus("MIC PERMISSION ERROR")
        } catch (e: Exception) {
            updateStatus("AUDIO ERROR: ${e.message}")
        }
    }

    private fun stopAudioCapture() {
        recording = false

        val recorder = audioRecord
        audioRecord = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            recordingThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recordingThread = null
    }

    // =====================================
    // AUDIO STATUS OVERLAY
    // =====================================

    private fun showStatusOverlay(message: String) {
        if (statusView != null) {
            updateStatus(message)
            return
        }

        try {
            val view = TextView(this).apply {
                text = message
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(
                    android.graphics.Color.rgb(35, 75, 110)
                )
                setPadding(14, 9, 14, 9)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
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
                        FloatingTranslatorService.EXTRA_MESSAGE,
                        "মাইক্রোফোন অডিও পরীক্ষা চলছে..."
                    )

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

    private fun removeStatusOverlay() {
        try {
            statusView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        statusView = null
    }

    // =====================================
    // ACCESSIBILITY LIFECYCLE
    // =====================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No screen content is collected.
    }

    override fun onInterrupt() {
        stopAudioCapture()
    }

    override fun onDestroy() {
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
        super.onDestroy()
    }
}
