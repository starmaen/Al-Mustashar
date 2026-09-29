package com.maen.almustashar

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SearchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        val etSearch = findViewById<EditText>(R.id.etSearch)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)

        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "ابحث في القوانين"

        etSearch.setOnEditorActionListener { _, _, _ ->
            val query = etSearch.text?.toString()?.trim()
            if (!query.isNullOrEmpty()) {
                tvEmpty.text = "قريبًا: نتائج البحث عن \"$query\""
            }
            true
        }
    }
}
