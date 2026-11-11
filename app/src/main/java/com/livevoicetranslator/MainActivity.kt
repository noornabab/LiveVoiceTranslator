
package com.livevoicetranslator

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            updateStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createMainScreen()
        requestPermissionsIfNeeded()
    }

    private fun createMainScreen() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(35, 35, 35, 35)
            setBackgroundColor(
                android.graphics.Color.rgb(25, 30, 42)
            )
        }

        val title = TextView(this).apply {
            text = "LIVE VOICE TRANSLATOR"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 25)
        }

        layout.addView(title)

        statusText = TextView(this).apply {
            text = "App প্রস্তুত হচ্ছে..."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(
                android.graphics.Color.rgb(100, 220, 150)
            )
            setPadding(0, 10, 0, 25)
        }

        layout.addView(statusText)

        val permissionButton = Button(this).apply {
            text = "GRANT APP PERMISSIONS"

            setOnClickListener {
                requestPermissionsIfNeeded()
            }
        }

        layout.addView(
            permissionButton,
            buttonLayoutParams()
        )

        val accessibilityButton = Button(this).apply {
            text = "ENABLE ACCESSIBILITY SERVICE"

            setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                } catch (_: Exception) {
                    showToast("Accessibility Settings খুলতে সমস্যা হয়েছে")
                }
            }
        }

        layout.addView(
            accessibilityButton,
            buttonLayoutParams()
        )

        val overlayButton = Button(this).apply {
            text = "ENABLE POPUP PERMISSION"

            setOnClickListener {
                openOverlaySettings()
            }
        }

        layout.addView(
            overlayButton,
            buttonLayoutParams()
        )

        val refreshButton = Button(this).apply {
            text = "CHECK STATUS"

            setOnClickListener {
                updateStatus()
            }
        }

        layout.addView(
            refreshButton,
            buttonLayoutParams()
        )

        setContentView(layout)
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 10
        }
    }

    private fun requestPermissionsIfNeeded() {

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )

        val missing = permissions.filter {
            checkSelfPermission(it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(
                missing.toTypedArray()
            )
        } else {
            updateStatus()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {

        val component = ComponentName(
            this,
            AccessibilityAudioService::class.java
        )

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices
            .split(":")
            .any {
                it.equals(
                    component.flattenToString(),
                    ignoreCase = true
                )
            }
    }

    private fun updateStatus() {

        val microphoneGranted =
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        val phoneGranted =
            checkSelfPermission(
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

        val accessibilityEnabled =
            isAccessibilityEnabled()

        val overlayEnabled =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)

        statusText.text = buildString {

            append("MICROPHONE: ")
            append(if (microphoneGranted) "GRANTED" else "NOT GRANTED")

            append("\nPHONE: ")
            append(if (phoneGranted) "GRANTED" else "NOT GRANTED")

            append("\nACCESSIBILITY: ")
            append(if (accessibilityEnabled) "ON" else "OFF")

            append("\nPOPUP: ")
            append(if (overlayEnabled) "ON" else "OFF")

            append("\n\n")

            append(
                if (
                    microphoneGranted &&
                    phoneGranted &&
                    accessibilityEnabled
                ) {
                    "কল করে Speaker ON রেখে পরীক্ষা করুন"
                } else {
                    "প্রয়োজনীয় Permission চালু করুন"
                }
            )
        }
    }

    private fun openOverlaySettings() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )

                startActivity(intent)

            } catch (_: Exception) {
                showToast("Popup Settings খুলতে সমস্যা হয়েছে")
            }
        } else {
            showToast("Popup permission already enabled")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onResume() {
        super.onResume()

        if (::statusText.isInitialized) {
            updateStatus()
        }
    }
}
