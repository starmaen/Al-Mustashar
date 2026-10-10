package com.maen.almustashar

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder

// بحث الويب داخل التطبيق (بدل فتح متصفح خارجي)
class WebSearchActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etQuery: EditText
    private lateinit var progress: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_web_search)

            webView = findViewById(R.id.webView)
            etQuery = findViewById(R.id.etWebQuery)
            progress = findViewById(R.id.webProgress)

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = WebViewClient()
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    progress.progress = newProgress
                }
            }

            val initial = intent.getStringExtra("query") ?: ""
            etQuery.setText(initial)
            if (initial.isNotBlank()) loadQuery(initial)

            findViewById<Button>(R.id.btnWebGo).setOnClickListener {
                val q = etQuery.text?.toString()?.trim() ?: ""
                if (q.isBlank()) {
                    Toast.makeText(this, "اكتب كلمة البحث", Toast.LENGTH_SHORT).show()
                } else {
                    loadQuery(q)
                }
            }
            findViewById<Button>(R.id.btnWebBack).setOnClickListener {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح البحث الداخلي", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadQuery(q: String) {
        try {
            val url = "https://www.google.com/search?q=" + URLEncoder.encode("$q القانون السوري", "UTF-8")
            webView.loadUrl(url)
        } catch (_: Exception) {
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
