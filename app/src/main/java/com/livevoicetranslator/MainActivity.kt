
package com.livevoicetranslator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView

    private var telephonyManager: TelephonyManager? = null
    private var callListener: PhoneStateListener? = null

    private var incomingCallRinging = false
    private var callWasActive = false
    private var permissionSetupStarted = false

    private val callPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                registerCallListener()
                checkMicrophonePermission()
            } else {
                statusText.text =
                    "Phone State permission প্রয়োজন"
            }
        }

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                requestNotificationPermission()
            } else {
                statusText.text =
                    "Microphone permission প্রয়োজন"
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            prepareVoiceService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()
        checkPermissionsAndPrepare()
    }

    // =====================================
    // PERMISSIONS
    // =====================================

    private fun checkPermissionsAndPrepare() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            registerCallListener()
            checkMicrophonePermission()

        } else {

            callPermissionLauncher.launch(
                Manifest.permission.READ_PHONE_STATE
            )
        }
    }

    private fun checkMicrophonePermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            requestNotificationPermission()

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )

        } else {

            prepareVoiceService()
        }
    }

    // =====================================
    // PREPARE FOREGROUND SERVICE
    // =====================================

    private fun prepareVoiceService() {

        try {

            val intent = Intent(
                this,
                VoiceForegroundService::class.java
            ).apply {
                action =
                    VoiceForegroundService.ACTION_PREPARE
            }

            ContextCompat.startForegroundService(
                this,
                intent
            )

            statusText.text =
                "প্রস্তুত। কলের অপেক্ষায় আছি..."

        } catch (e: Exception) {

            statusText.text =
                "Service Error: ${e.message}"
        }
    }

    // =====================================
    // CALL DETECTION
    // =====================================

    @Suppress("DEPRECATION")
    private fun registerCallListener() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {

            telephonyManager =
                getSystemService(
                    Context.TELEPHONY_SERVICE
                ) as TelephonyManager

            callListener = object : PhoneStateListener() {

                override fun onCallStateChanged(
                    state: Int,
                    phoneNumber: String?
                ) {

                    super.onCallStateChanged(
                        state,
                        phoneNumber
                    )

                    runOnUiThread {

                        when (state) {

                            TelephonyManager.CALL_STATE_RINGING -> {

                                incomingCallRinging = true
                                callWasActive = true

                                statusText.text =
                                    "📞 INCOMING CALL"

                                showCallPopup(
                                    "INCOMING CALL",
                                    "ফোনে কল এসেছে। কল ধরুন এবং Speaker চালু করুন।"
                                )
                            }

                            TelephonyManager.CALL_STATE_OFFHOOK -> {

                                callWasActive = true

                                val callStatus =
                                    if (incomingCallRinging) {
                                        "INCOMING CALL CONNECTED"
                                    } else {
                                        "OUTGOING / ACTIVE CALL"
                                    }

                                statusText.text =
                                    "🎙 কল চলাকালীন Listening চালু হচ্ছে..."

                                showCallPopup(
                                    callStatus,
                                    "কথা শনাক্ত করার চেষ্টা চলছে..."
                                )

                                startCallListening()
                            }

                            TelephonyManager.CALL_STATE_IDLE -> {

                                if (callWasActive) {

                                    stopCallListening()

                                    statusText.text =
                                        "কল শেষ। Listening বন্ধ।"

                                    showCallPopup(
                                        "CALL ENDED",
                                        "কল শেষ হয়েছে। মাইক্রোফোন বন্ধ করা হয়েছে।"
                                    )
                                }

                                incomingCallRinging = false
                                callWasActive = false
                            }
                        }
                    }
                }
            }

            telephonyManager?.listen(
                callListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )

        } catch (e: Exception) {

            statusText.text =
                "Call Detection Error: ${e.message}"
        }
    }

    // =====================================
    // START LISTENING DURING CALL
    // =====================================

    private fun startCallListening() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text =
                "Microphone permission নেই"
            return
        }

        try {

            val intent = Intent(
                this,
                VoiceForegroundService::class.java
            ).apply {
                action =
                    VoiceForegroundService.ACTION_START
            }

            startService(intent)

        } catch (e: Exception) {

            statusText.text =
                "Listening চালু করা যায়নি: ${e.message}"
        }
    }

    // =====================================
    // STOP LISTENING AFTER CALL
    // =====================================

    private fun stopCallListening() {

        try {

            val intent = Intent(
                this,
                VoiceForegroundService::class.java
            ).apply {
                action =
                    VoiceForegroundService.ACTION_STOP_LISTENING
            }

            startService(intent)

        } catch (_: Exception) {
        }
    }

    // =====================================
    // FLOATING POPUP
    // =====================================

    private fun showCallPopup(
        callStatus: String,
        message: String
    ) {

        try {

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(this)
            ) {

                statusText.text =
                    "Floating Popup permission প্রয়োজন"

                return
            }

            val showIntent = Intent(
                this,
                FloatingTranslatorService::class.java
            ).apply {
                action =
                    FloatingTranslatorService.ACTION_SHOW
            }

            startService(showIntent)

            val updateIntent = Intent(
                this,
                FloatingTranslatorService::class.java
            ).apply {

                action =
                    FloatingTranslatorService.ACTION_UPDATE

                putExtra(
                    FloatingTranslatorService.EXTRA_STATUS,
                    callStatus
                )

                putExtra(
                    FloatingTranslatorService.EXTRA_MESSAGE,
                    message
                )
            }

            startService(updateIntent)

        } catch (e: Exception) {

            statusText.text =
                "Popup Error: ${e.message}"
        }
    }

    // =====================================
    // MANUAL SERVICE CONTROLS
    // =====================================

    private fun prepareAgain() {
        checkMicrophonePermission()
    }

    private fun stopEntireService() {

        try {

            val intent = Intent(
                this,
                VoiceForegroundService::class.java
            ).apply {
                action =
                    VoiceForegroundService.ACTION_STOP
            }

            startService(intent)

            statusText.text =
                "Voice service সম্পূর্ণ বন্ধ"

        } catch (e: Exception) {

            statusText.text =
                "Stop Error: ${e.message}"
        }
    }

    // =====================================
    // USER INTERFACE
    // =====================================

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
            setBackgroundColor(
                android.graphics.Color.WHITE
            )
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "LIVE VOICE TRANSLATOR"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(
                android.graphics.Color.rgb(20, 75, 130)
            )
            setPadding(5, 15, 5, 10)
        }

        val subtitle = TextView(this).apply {
            text = "Automatic Call Voice Translation"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(
                android.graphics.Color.DKGRAY
            )
            setPadding(5, 5, 5, 25)
        }

        statusText = TextView(this).apply {
            text = "প্রস্তুত হচ্ছে..."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(
                android.graphics.Color.rgb(20, 100, 60)
            )
            setPadding(10, 25, 10, 25)
        }

        val prepareButton = Button(this).apply {
            text = "PREPARE CALL TRANSLATOR"
            setOnClickListener {
                prepareAgain()
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP SERVICE"
            setOnClickListener {
                stopEntireService()
            }
        }

        val info = TextView(this).apply {
            text = """
                Automatic Mode

                • Incoming call detection
                • Outgoing call detection
                • Listening during active calls
                • Listening stops after call ends

                Supported languages:
                English
                Arabic
                Hindi
                Urdu
                Bengali

                বাংলা কথা বাংলাতেই দেখানোর চেষ্টা করবে।
                অন্য ভাষা বাংলায় অনুবাদ করবে।

                কলের সময় Speaker চালু রাখুন।
            """.trimIndent()

            textSize = 15f
            setTextColor(
                android.graphics.Color.DKGRAY
            )
            setPadding(12, 20, 12, 20)
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(statusText)
        content.addView(prepareButton)
        content.addView(stopButton)
        content.addView(info)

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    // =====================================
    // CLEANUP
    // =====================================

    @Suppress("DEPRECATION")
    override fun onDestroy() {

        try {

            callListener?.let {
                telephonyManager?.listen(
                    it,
                    PhoneStateListener.LISTEN_NONE
                )
            }

        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
