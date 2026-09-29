package com.maen.almustashar
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
class QueriesActivity : AppCompatActivity() {
    private lateinit var adapter: QueryAdapter
    private val items = mutableListOf<Query>()
    private lateinit var tvEmpty: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queries)
        tvEmpty = findViewById(R.id.tvEmpty)
        val recycler = findViewById<RecyclerView>(R.id.recyclerQueries)
        adapter = QueryAdapter(items) { q ->
            val i = Intent(this, QueryDetailActivity::class.java)
            i.putExtra("query_id", q.id)
            startActivity(i)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }
    override fun onResume() { super.onResume(); load() }
    private fun load() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { tvEmpty.visibility = View.VISIBLE; return }
        FirebaseFirestore.getInstance().collection("queries")
            .whereEqualTo("userId", user.uid)
            .get()
            .addOnSuccessListener { result ->
                items.clear()
                for (doc in result) {
                    val q = doc.toObject(Query::class.java)
                    if (q.answer.isNotEmpty() && q.answer.length > 20) {
                        items.add(q.copy(id = doc.id))
                    } else {
                        FirebaseFirestore.getInstance().collection("queries").document(doc.id).delete()
                    }
                }
                items.sortByDescending { it.timestamp }
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
    }
}
