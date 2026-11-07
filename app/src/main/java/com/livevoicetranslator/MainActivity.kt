package com.livevoicetranslator

import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import android.text.Html
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var inputText: EditText
    private lateinit var statusText: TextView
    private lateinit var resultLayout: LinearLayout

    private val executor = Executors.newFixedThreadPool(4)

    private val languages = listOf(
        "English" to "en",
        "Hindi" to "hi",
        "Urdu" to "ur",
        "Arabic" to "ar"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
    }

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "LIVE VOICE TRANSLATOR"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 75, 130))
            setPadding(5, 15, 5, 10)
        }

        val subtitle = TextView(this).apply {
            text = "বাংলা থেকে চার ভাষায় অনুবাদ"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(5, 5, 5, 25)
        }

        val inputLabel = TextView(this).apply {
            text = "আপনার বাংলা Reply লিখুন:"
            textSize = 17f
            setTextColor(Color.BLACK)
        }

        inputText = EditText(this).apply {
            hint = "এখানে বাংলায় লিখুন..."
            textSize = 17f
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            setPadding(14, 12, 14, 12)
            setBackgroundColor(Color.rgb(245, 245, 245))
        }

        val translateButton = Button(this).apply {
            text = "TRANSLATE"
            setOnClickListener {
                translateText()
            }
        }

        statusText = TextView(this).apply {
            text = "অনুবাদের জন্য প্রস্তুত"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(5, 15, 5, 15)
        }

        val resultTitle = TextView(this).apply {
            text = "TRANSLATION RESULTS"
            textSize = 18f
            setTextColor(Color.rgb(20, 75, 130))
            setPadding(5, 15, 5, 12)
        }

        resultLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(inputLabel)
        content.addView(inputText)
        content.addView(translateButton)
        content.addView(statusText)
        content.addView(resultTitle)
        content.addView(resultLayout)

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

    private fun translateText() {

        val original = inputText.text.toString().trim()

        if (original.isEmpty()) {
            inputText.error = "বাংলা লেখা লিখুন"
            return
        }

        resultLayout.removeAllViews()
        statusText.text = "অনুবাদ হচ্ছে..."

        val loading = TextView(this).apply {
            text = "Please wait..."
            textSize = 16f
            setPadding(10, 15, 10, 15)
            setTextColor(Color.GRAY)
        }

        resultLayout.addView(loading)

        languages.forEach { language ->

            executor.execute {

                val translated = translate(
                    original,
                    language.second
                )

                runOnUiThread {

                    if (resultLayout.indexOfChild(loading) >= 0) {
                        resultLayout.removeView(loading)
                    }

                    val result = TextView(this).apply {
                        text = if (translated != null) {
                            "${language.first}\n$translated"
                        } else {
                            "${language.first}\nঅনুবাদ পাওয়া যায়নি"
                        }

                        textSize = 17f
                        setTextColor(Color.BLACK)
                        setPadding(14, 14, 14, 14)
                        setBackgroundColor(
                            Color.rgb(242, 246, 250)
                        )
                    }

                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )

                    params.setMargins(0, 6, 0, 6)

                    resultLayout.addView(result, params)

                    if (resultLayout.childCount >= languages.size) {
                        statusText.text = "অনুবাদ সম্পন্ন"
                    }
                }
            }
        }
    }

    private fun translate(
        text: String,
        targetLanguage: String
    ): String? {

        var connection: HttpURLConnection? = null

        return try {

            val query = URLEncoder.encode(text, "UTF-8")

            val address =
                "https://api.mymemory.translated.net/get" +
                "?q=$query&langpair=bn%7C$targetLanguage"

            connection = URL(address)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(response)

            val responseData = json.optJSONObject("responseData")

            val translated = responseData
                ?.optString("translatedText")

            if (
                translated.isNullOrBlank() ||
                translated == "QUERY LENGTH LIMIT EXCEEDED"
            ) {
                null
            } else {
                Html.fromHtml(
                    translated,
                    Html.FROM_HTML_MODE_LEGACY
                ).toString()
            }

        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
