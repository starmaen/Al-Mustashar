package com.maen.almustashar

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class CasesActivity : AppCompatActivity() {

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os ->
                val success = CaseBackupManager.exportBackup(this, allCases, os)
                if (success) {
                    Toast.makeText(this, "تم تصدير الدعاوى بنجاح. تنبيه: ملفات المرفقات نفسها غير مشمولة — انسخها سحابياً من داخل كل دعوى", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "فشل تصدير النسخة الاحتياطية", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { ins ->
                val imported = CaseBackupManager.importBackup(ins)
                if (imported != null) {
                    saveImportedCases(imported)
                } else {
                    Toast.makeText(this, "فشل قراءة ملف النسخة الاحتياطية", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveImportedCases(imported: List<Case>) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance().collection("cases")
        var count = 0
        for (c in imported) {
            val toSave = c.copy(userId = user.uid, id = "")
            db.add(toSave).addOnSuccessListener {
                count++
                if (count == imported.size) {
                    Toast.makeText(this, "تم استعادة $count ملف بنجاح", Toast.LENGTH_SHORT).show()
                    load()
                }
            }
        }
    }


    private lateinit var adapter: CaseAdapter
    private val allCases = mutableListOf<Case>()
    private val displayedCases = mutableListOf<Case>()
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var etSearch: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cases)

        // إذن الإشعارات لتذكير الجلسات (أندرويد 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        tvEmpty = findViewById(R.id.tvEmpty)
        tvCount = findViewById(R.id.tvCasesCount)
        etSearch = findViewById(R.id.etSearchCases)
        val recycler = findViewById<RecyclerView>(R.id.recyclerCases)
        val fab = findViewById<FloatingActionButton>(R.id.fabAdd)

        adapter = CaseAdapter(displayedCases) { case ->
            val i = Intent(this, CaseEditActivity::class.java)
            i.putExtra("case_id", case.id)
            startActivity(i)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        
        findViewById<Button>(R.id.btnExportBackup).setOnClickListener {
            if (allCases.isEmpty()) {
                Toast.makeText(this, "لا توجد قضايا للتصدير", Toast.LENGTH_SHORT).show()
            } else {
                exportLauncher.launch("almustashar_cases_backup.json")
            }
        }

        findViewById<Button>(R.id.btnImportBackup).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        fab.setOnClickListener {
            startActivity(Intent(this, CaseEditActivity::class.java))
        }

        // محرك البحث الفوري اللحظي داخل الأرشيف
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterCases(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "يرجى تسجيل الدخول لعرض أرشيف الدعاوى الخاص بك"
            return
        }

        FirebaseFirestore.getInstance().collection("cases")
            .whereEqualTo("userId", user.uid)
            .get()
            .addOnSuccessListener { qs ->
                allCases.clear()
                for (doc in qs.documents) {
                    val c = doc.toObject(Case::class.java)
                    if (c != null) {
                        allCases.add(c.copy(id = doc.id))
                    }
                }
                // ترتيب حسب موعد الجلسة القادمة أو آخر تحديث
                allCases.sortByDescending { it.timestamp }
                filterCases(etSearch.text?.toString()?.trim() ?: "")
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "فشل جلب الأرشيف: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun normAr(s: String): String {
        return s.lowercase()
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ة", "ه").replace("ى", "ي")
            .replace(Regex("[^a-zA-Z0-9\u0621-\u064A]"), "")
    }

    // فهرس الأرشيف: بحث فوري باسم الموكل أو الرقم أو الموضوع (بكل الصيغ) ثم فتح الملف
    private fun filterCases(query: String) {
        displayedCases.clear()
        if (query.isBlank()) {
            displayedCases.addAll(allCases)
        } else {
            val terms = query.split(Regex("\\s+")).map { normAr(it) }.filter { it.length > 1 }
            if (terms.isEmpty()) {
                displayedCases.addAll(allCases)
            } else {
                for (c in allCases) {
                    val hay = normAr(
                        listOf(
                            c.title, c.basisNumber, c.caseYear, c.clientName, c.clientRole,
                            c.clientPhone, c.opponentName, c.opponentLawyer, c.court,
                            c.chamber, c.judgeName, c.date, c.nextSessionDate,
                            c.status, c.summary
                        ).joinToString(" ")
                    )
                    if (terms.all { hay.contains(it) }) displayedCases.add(c)
                }
            }
        }

        tvCount.text = "إجمالي الدعاوى: ${allCases.size} (المعروض: ${displayedCases.size})"
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (displayedCases.isEmpty()) View.VISIBLE else View.GONE
    }
}
