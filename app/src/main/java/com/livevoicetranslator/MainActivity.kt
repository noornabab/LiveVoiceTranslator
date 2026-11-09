
package com.livevoicetranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var telephonyManager: TelephonyManager

    private lateinit var statusText: TextView

    private var listenerRegistered = false

    private var incomingCallRinging = false
    private var callActive = false
    private var callPopupShown = false
    private var listeningStarted = false

    // =====================================
    // PERMISSION REQUEST
    // =====================================

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val phonePermission =
                permissions[Manifest.permission.READ_PHONE_STATE] == true ||
                    checkSelfPermission(
                        Manifest.permission.READ_PHONE_STATE
                    ) == PackageManager.PERMISSION_GRANTED

            if (phonePermission) {
                registerPhoneListener()
            }

            if (
                checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                prepareVoiceService()
            }

            if (!phonePermission) {
                statusText.text =
                    "Phone permission প্রয়োজন"
            }
        }

    // =====================================
    // PHONE STATE LISTENER
    // =====================================

    private val phoneStateListener =
        object : PhoneStateListener() {

            override fun onCallStateChanged(
                state: Int,
                phoneNumber: String?
            ) {

                super.onCallStateChanged(
                    state,
                    phoneNumber
                )

                when (state) {

                    // =================================
                    // INCOMING CALL
                    // =================================

                    TelephonyManager.CALL_STATE_RINGING -> {

                        incomingCallRinging = true

                        if (!callPopupShown) {

                            callPopupShown = true

                            showCallPopup()

                            statusText.text =
                                "Incoming call detected"
                        }
                    }

                    // =================================
                    // INCOMING ANSWERED / OUTGOING CALL
                    // =================================

                    TelephonyManager.CALL_STATE_OFFHOOK -> {

                        if (!callActive) {

                            callActive = true

                            // Incoming অথবা Outgoing,
                            // দুই ক্ষেত্রেই Popup নিশ্চিত করা।
                            if (!callPopupShown) {

                                callPopupShown = true

                                showCallPopup()
                            }

                            statusText.text =
                                if (incomingCallRinging) {
                                    "Incoming call connected"
                                } else {
                                    "Outgoing call connected"
                                }

                            startCallListening()
                        }
                    }

                    // =================================
                    // CALL ENDED
                    // =================================

                    TelephonyManager.CALL_STATE_IDLE -> {

                        if (
                            callActive ||
                            incomingCallRinging ||
                            callPopupShown
                        ) {

                            stopCallListening()

                            hideCallPopup()

                            incomingCallRinging = false
                            callActive = false
                            callPopupShown = false
                            listeningStarted = false

                            statusText.text =
                                "Call ended. Waiting for next call."
                        }
                    }
                }
            }
        }

    // =====================================
    // ON CREATE
    // =====================================

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        telephonyManager =
            getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        createMainScreen()

        requestAppPermissions()
    }

    // =====================================
    // MAIN SCREEN
    // =====================================

    private fun createMainScreen() {

        val mainLayout = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER

            setPadding(
                35,
                35,
                35,
                35
            )

            setBackgroundColor(
                android.graphics.Color.rgb(25, 30, 42)
            )
        }

        val title = TextView(this).apply {

            text = "LIVE VOICE TRANSLATOR"

            textSize = 22f

            setTextColor(
                android.graphics.Color.WHITE
            )

            gravity = Gravity.CENTER

            setPadding(0, 0, 0, 25)
        }

        mainLayout.addView(title)

        statusText = TextView(this).apply {

            text = "App প্রস্তুত হচ্ছে..."

            textSize = 15f

            setTextColor(
                android.graphics.Color.rgb(100, 220, 150)
            )

            gravity = Gravity.CENTER

            setPadding(0, 10, 0, 25)
        }

        mainLayout.addView(statusText)

        // =================================
        // START MIC TEST
        // =================================

        val startButton = Button(this).apply {

            text = "START MIC TEST"

            setOnClickListener {

                if (!hasMicrophonePermission()) {

                    requestAppPermissions()

                    return@setOnClickListener
                }

                startVoiceService(
                    VoiceForegroundService.ACTION_START
                )

                statusText.text =
                    "Microphone test started"
            }
        }

        mainLayout.addView(
            startButton,
            buttonLayoutParams()
        )

        // =================================
        // STOP MIC TEST
        // =================================

        val stopButton = Button(this).apply {

            text = "STOP MIC TEST"

            setOnClickListener {

                stopCallListening()

                statusText.text =
                    "Microphone test stopped"
            }
        }

        mainLayout.addView(
            stopButton,
            buttonLayoutParams()
        )

        // =================================
        // STOP SERVICE
        // =================================

        val stopServiceButton = Button(this).apply {

            text = "STOP SERVICE"

            setOnClickListener {

                stopCallListening()

                val intent = Intent(
                    this@MainActivity,
                    VoiceForegroundService::class.java
                ).apply {
                    action = VoiceForegroundService.ACTION_STOP
                }

                try {
                    startService(intent)
                } catch (_: Exception) {
                }

                hideCallPopup()

                incomingCallRinging = false
                callActive = false
                callPopupShown = false
                listeningStarted = false

                statusText.text =
                    "Service stopped"
            }
        }

        mainLayout.addView(
            stopServiceButton,
            buttonLayoutParams()
        )

        // =================================
        // OVERLAY PERMISSION
        // =================================

        val overlayButton = Button(this).apply {

            text = "ENABLE POPUP PERMISSION"

            setOnClickListener {

                openOverlaySettings()
            }
        }

        mainLayout.addView(
            overlayButton,
            buttonLayoutParams()
        )

        setContentView(mainLayout)
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 10
        }
    }

    // =====================================
    // REQUEST PERMISSIONS
    // =====================================

    private fun requestAppPermissions() {

        val requiredPermissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {

            requiredPermissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val missingPermissions =
            requiredPermissions.filter {

                checkSelfPermission(it) !=
                    PackageManager.PERMISSION_GRANTED
            }

        if (missingPermissions.isNotEmpty()) {

            permissionLauncher.launch(
                missingPermissions.toTypedArray()
            )

        } else {

            registerPhoneListener()
            prepareVoiceService()
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {

            statusText.text =
                "Popup permission চালু করুন"
        }
    }

    private fun hasMicrophonePermission(): Boolean {

        return checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // =====================================
    // OVERLAY SETTINGS
    // =====================================

    private fun openOverlaySettings() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )

            startActivity(intent)

        } else {

            Toast.makeText(
                this,
                "Popup permission already enabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // =====================================
    // PREPARE VOICE SERVICE
    // =====================================

    private fun prepareVoiceService() {

        if (!hasMicrophonePermission()) return

        val intent = Intent(
            this,
            VoiceForegroundService::class.java
        ).apply {

            action =
                VoiceForegroundService.ACTION_PREPARE
        }

        try {

            startService(intent)

        } catch (e: Exception) {

            statusText.text =
                "Voice service প্রস্তুত করা যায়নি"
        }
    }

    // =====================================
    // REGISTER PHONE LISTENER
    // =====================================

    private fun registerPhoneListener() {

        if (listenerRegistered) return

        if (
            checkSelfPermission(
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {

            telephonyManager.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )

            listenerRegistered = true

            statusText.text =
                "Incoming & Outgoing call monitoring active"

            // App চলমান কলের মধ্যে খোলা হলে
            // বর্তমান OFFHOOK অবস্থাও পরীক্ষা করবে।
            if (
                telephonyManager.callState ==
                TelephonyManager.CALL_STATE_OFFHOOK
            ) {

                callActive = true
                callPopupShown = true

                showCallPopup()
                startCallListening()
            }

        } catch (e: Exception) {

            statusText.text =
                "Phone monitoring failed"
        }
    }

    // =====================================
    // START CALL LISTENING
    // =====================================

    private fun startCallListening() {

        if (listeningStarted) return

        if (!hasMicrophonePermission()) {

            statusText.text =
                "Microphone permission প্রয়োজন"

            return
        }

        listeningStarted = true

        startVoiceService(
            VoiceForegroundService.ACTION_START
        )
    }

    // =====================================
    // STOP CALL LISTENING
    // =====================================

    private fun stopCallListening() {

        listeningStarted = false

        val intent = Intent(
            this,
            VoiceForegroundService::class.java
        ).apply {

            action =
                VoiceForegroundService.ACTION_STOP_LISTENING
        }

        try {

            startService(intent)

        } catch (_: Exception) {
        }
    }

    // =====================================
    // START VOICE SERVICE
    // =====================================

    private fun startVoiceService(actionName: String) {

        val intent = Intent(
            this,
            VoiceForegroundService::class.java
        ).apply {

            action = actionName
        }

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                startForegroundService(intent)

            } else {

                startService(intent)
            }

        } catch (e: Exception) {

            statusText.text =
                "Voice service start failed: ${e.message}"
        }
    }

    // =====================================
    // SHOW CALL POPUP
    // =====================================

    private fun showCallPopup() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {

            statusText.text =
                "Popup permission চালু করুন"

            return
        }

        val intent = Intent(
            this,
            FloatingTranslatorService::class.java
        ).apply {

            action =
                FloatingTranslatorService.ACTION_SHOW
        }

        try {

            startService(intent)

        } catch (e: Exception) {

            statusText.text =
                "Popup খুলতে সমস্যা হয়েছে"
        }
    }

    // =====================================
    // HIDE CALL POPUP
    // =====================================

    private fun hideCallPopup() {

        val intent = Intent(
            this,
            FloatingTranslatorService::class.java
        ).apply {

            action =
                FloatingTranslatorService.ACTION_HIDE
        }

        try {

            startService(intent)

        } catch (_: Exception) {
        }
    }

    // =====================================
    // ON RESUME
    // =====================================

    override fun onResume() {

        super.onResume()

        if (
            checkSelfPermission(
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            registerPhoneListener()
        }
    }

    // =====================================
    // ON DESTROY
    // =====================================

    override fun onDestroy() {

        if (listenerRegistered) {

            try {

                telephonyManager.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_NONE
                )

            } catch (_: Exception) {
            }

            listenerRegistered = false
        }

        super.onDestroy()
    }
}
