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
    private lateinit var tvStats: TextView
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
        tvStats = findViewById(R.id.tvCasesStats)
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

        findViewById<Button>(R.id.btnBackupSettings).setOnClickListener {
            showBackupSettings()
        }

        findViewById<Button>(R.id.btnGeneralDrafting).setOnClickListener {
            try {
                startActivity(Intent(this, DraftingStudioActivity::class.java))
            } catch (_: Exception) {
            }
        }

        // جدولة النسخ التلقائي اليومي حسب اختيار المستخدم (يدوي فقط = إلغاء)
        try {
            AutoBackupScheduler.ensure(this)
        } catch (_: Exception) {
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
                updateStats()
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

    // إعدادات النسخ: الوضع (يدوي/تلقائي/معاً) + الوجهات (الهاتف/Drive)
    // ملاحظة: الذاكرة الخارجية (SD) يدوية فقط عبر زر التصدير (النظام يمنع الكتابة الصامتة لها)
    private fun showBackupSettings() {
        val modes = arrayOf("📝 يدوي فقط", "🤖 تلقائي يومي فقط", "🔀 الاثنان معاً")
        var mode = BackupPrefs.mode(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val rg = android.widget.RadioGroup(this)
        val radios = modes.mapIndexed { i, t ->
            android.widget.RadioButton(this).apply {
                text = t
                isChecked = (i == mode)
                setOnCheckedChangeListener { _, checked -> if (checked) mode = i }
            }
        }
        radios.forEach { rg.addView(it) }
        val cbPhone = android.widget.CheckBox(this).apply {
            text = "📱 الهاتف (تلقائي: ملفات التطبيق + التنزيلات)"
            isChecked = BackupPrefs.destPhone(this@CasesActivity)
        }
        val cbDrive = android.widget.CheckBox(this).apply {
            text = "☁️ Google Drive (يحتاج دخول Google مرة واحدة)"
            isChecked = BackupPrefs.destDrive(this@CasesActivity)
        }
        val tvLast = android.widget.TextView(this).apply {
            val last = BackupPrefs.lastAuto(this@CasesActivity)
            text = if (last.isBlank()) "آخر نسخة تلقائية: لم تتم بعد" else "آخر نسخة تلقائية: $last"
            textSize = 12f
        }
        val tvNote = android.widget.TextView(this).apply {
            text = "💾 الذاكرة الخارجية (SD): عبر زر «تصدير» — تختار المكان بنفسك كل مرة."
            textSize = 12f
        }
        layout.addView(rg)
        layout.addView(cbPhone)
        layout.addView(cbDrive)
        layout.addView(tvLast)
        layout.addView(tvNote)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚙️ إعدادات النسخ الاحتياطي")
            .setView(layout)
            .setPositiveButton("حفظ") { _, _ ->
                BackupPrefs.setMode(this, mode)
                BackupPrefs.setDest(this, cbPhone.isChecked, cbDrive.isChecked)
                try {
                    if (mode == BackupPrefs.MODE_MANUAL) AutoBackupScheduler.cancel(this)
                    else AutoBackupScheduler.ensure(this)
                } catch (_: Exception) {
                }
                val m = when (mode) {
                    0 -> "يدوي فقط"
                    1 -> "تلقائي يومي"
                    else -> "الاثنان معاً"
                }
                Toast.makeText(this, "حُفظ: $m", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun daysUntil(dateStr: String): Long? {
        return try {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dateStr)
                ?: return null
            val today = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.time
            (day.time - today.time) / (24 * 3600 * 1000L)
        } catch (_: Exception) {
            null
        }
    }

    private fun updateStats() {
        try {
            val active = allCases.count { it.status.isBlank() || it.status.contains("نظر") }
            var weekSessions = 0
            var appealSoon = 0
            for (c in allCases) {
                daysUntil(c.nextSessionDate)?.let { if (it in 0..7) weekSessions++ }
                daysUntil(c.appealDeadline)?.let { if (it <= 30) appealSoon++ }
            }
            val parts = mutableListOf("📊 قيد النظر: $active")
            if (weekSessions > 0) parts.add("📅 جلسات هذا الأسبوع: $weekSessions")
            if (appealSoon > 0) parts.add("⚖️ طعون قريبة/متأخرة: $appealSoon")
            tvStats.text = parts.joinToString("  •  ")
        } catch (_: Exception) {
        }
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
                            c.status, c.summary, c.appealType, c.appealDeadline
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
