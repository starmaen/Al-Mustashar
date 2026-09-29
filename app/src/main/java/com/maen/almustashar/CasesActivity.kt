package com.maen.almustashar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class CasesActivity : AppCompatActivity() {

    private lateinit var adapter: CaseAdapter
    private val items = mutableListOf<Case>()
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cases)

        tvEmpty = findViewById(R.id.tvEmpty)
        val recycler = findViewById<RecyclerView>(R.id.recyclerCases)
        val fab = findViewById<FloatingActionButton>(R.id.fabAdd)

        adapter = CaseAdapter(items) { case ->
            val i = Intent(this, CaseEditActivity::class.java)
            i.putExtra("case_id", case.id)
            startActivity(i)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        fab.setOnClickListener {
            startActivity(Intent(this, CaseEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "يجب تسجيل الدخول أولاً"
            return
        }
        FirebaseFirestore.getInstance().collection("cases")
            .whereEqualTo("userId", user.uid)
            .get()
            .addOnSuccessListener { result ->
                items.clear()
                for (doc in result) {
                    val c = doc.toObject(Case::class.java)
                    items.add(c.copy(id = doc.id))
                }
                items.sortByDescending { it.timestamp }
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "فشل التحميل: ${it.message}"
            }
    }
}
