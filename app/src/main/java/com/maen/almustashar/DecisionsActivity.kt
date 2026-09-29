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

class DecisionsActivity : AppCompatActivity() {

    private lateinit var adapter: DecisionAdapter
    private val items = mutableListOf<CourtDecision>()
    private lateinit var tvEmpty: TextView
    private var caseId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decisions)

        caseId = intent.getStringExtra("case_id") ?: ""

        tvEmpty = findViewById(R.id.tvEmpty)
        val recycler = findViewById<RecyclerView>(R.id.recyclerDecisions)
        val fab = findViewById<FloatingActionButton>(R.id.fabAdd)

        adapter = DecisionAdapter(items) { decision ->
            val i = Intent(this, DecisionEditActivity::class.java)
            i.putExtra("case_id", caseId)
            i.putExtra("decision_id", decision.id)
            startActivity(i)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        fab.setOnClickListener {
            val i = Intent(this, DecisionEditActivity::class.java)
            i.putExtra("case_id", caseId)
            startActivity(i)
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
            tvEmpty.text = "يجب تسجيل الدخول"
            return
        }
        FirebaseFirestore.getInstance().collection("decisions")
            .whereEqualTo("userId", user.uid)
            .whereEqualTo("caseId", caseId)
            .get()
            .addOnSuccessListener { result ->
                items.clear()
                for (doc in result) {
                    val d = doc.toObject(CourtDecision::class.java)
                    items.add(d.copy(id = doc.id))
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
