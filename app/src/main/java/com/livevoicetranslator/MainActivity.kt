
package com.livevoicetranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var startButton: Button

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                status.text = "Microphone permission granted"
                startButton.isEnabled = true
            } else {
                status.text = "Microphone permission required"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
        checkPermission()
    }

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 30, 28, 30)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "LIVE VOICE TRANSLATOR"
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Checking permission..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(10, 30, 10, 30)
        }

        startButton = Button(this).apply {
            text = "START TRANSLATOR"
            isEnabled = false
            setOnClickListener {
                status.text = "Translator is ready"
                Toast.makeText(
                    this@MainActivity,
                    "Ready",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP TRANSLATOR"
            setOnClickListener {
                status.text = "Translator stopped"
            }
        }

        val languages = TextView(this).apply {
            text = """
                Incoming Voice: Bangla

                Reply Translation:

                English
                Hindi
                Urdu
                Arabic
            """.trimIndent()

            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(10, 30, 10, 10)
        }

        root.addView(title)
        root.addView(status)
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(languages)

        setContentView(root)
    }

    private fun checkPermission() {

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            status.text = "Microphone permission granted"
            startButton.isEnabled = true
        } else {
            permissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }
}
