package com.maen.almustashar

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

// تفضيلات النسخ الاحتياطي: الوضع (يدوي/تلقائي/معاً) + الوجهات (الهاتف/Drive)
object BackupPrefs {
    private const val PREFS = "backup_prefs"
    const val MODE_MANUAL = 0
    const val MODE_AUTO = 1
    const val MODE_BOTH = 2

    fun mode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("mode", MODE_BOTH)

    fun setMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("mode", mode).apply()
    }

    fun destPhone(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dest_phone", true)

    fun destDrive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dest_drive", true)

    fun setDest(ctx: Context, phone: Boolean, drive: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("dest_phone", phone).putBoolean("dest_drive", drive).apply()
    }

    fun lastAuto(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_auto", "") ?: ""

    fun setLastAuto(ctx: Context, s: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_auto", s).apply()
    }
}

object AutoBackupScheduler {
    private const val WORK = "auto_cases_backup"

    fun ensure(context: Context) {
        try {
            if (BackupPrefs.mode(context) == BackupPrefs.MODE_MANUAL) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK)
                return
            }
            val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        } catch (_: Exception) {
        }
    }
}

// نسخة تلقائية يومية صامتة: ملف الجهاز + (مجلد التنزيلات) + Drive عند الإمكان.
// الذاكرة الخارجية (SD) تبقى يدوية عبر منتقي الملفات (نظام أندرويد يمنع الكتابة الصامتة لها).
class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            if (BackupPrefs.mode(context) == BackupPrefs.MODE_MANUAL) return Result.success()
            val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
            val qs = FirebaseFirestore.getInstance().collection("cases")
                .whereEqualTo("userId", user.uid).get().await()
            val cases = qs.documents.mapNotNull {
                try {
                    it.toObject(Case::class.java)?.copy(id = it.id)
                } catch (_: Exception) {
                    null
                }
            }
            if (cases.isEmpty()) {
                BackupPrefs.setLastAuto(context, "لا دعاوى للنسخ")
                return Result.success()
            }
            val baos = ByteArrayOutputStream()
            if (!CaseBackupManager.exportBackup(context, cases, baos)) return Result.success()
            val bytes = baos.toByteArray()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            val name = "auto_backup_$stamp.json"
            var where = mutableListOf<String>()

            if (BackupPrefs.destPhone(context)) {
                // 1) مجلد التطبيق الخاص (دائماً يعمل)
                try {
                    val dir = File(context.filesDir, "auto_backups").apply { mkdirs() }
                    File(dir, name).writeBytes(bytes)
                    // احتفاظ بآخر 7 نسخ فقط
                    dir.listFiles()?.sortedByDescending { it.lastModified() }
                        ?.drop(7)?.forEach { try { it.delete() } catch (_: Exception) {} }
                    where.add("الهاتف")
                } catch (_: Exception) {
                }
                // 2) مجلد التنزيلات الظاهر للمستخدم (أندرويد 10+ بلا إذن)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AlMustashar")
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (BackupPrefs.destDrive(context)) {
                if (uploadToDriveSilent(context, name, bytes)) where.add("Drive")
            }

            val status = if (where.isEmpty()) "تعذر (راجع الإعدادات)"
            else "تم: ${where.joinToString("+")} (${cases.size} دعوى)"
            BackupPrefs.setLastAuto(
                context,
                "$status — ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}"
            )
            Result.success()
        } catch (_: Exception) {
            Result.success() // لا إعادة محاولة مزعجة — المحاولة القادمة غداً
        }
    }

    private fun uploadToDriveSilent(context: Context, name: String, bytes: ByteArray): Boolean {
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
            val scope = Scope("https://www.googleapis.com/auth/drive.file")
            if (!GoogleSignIn.hasPermissions(account, scope)) return false
            val token = GoogleAuthUtil.getToken(
                context, account.account!!, "oauth2:https://www.googleapis.com/auth/drive.file"
            )
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS).build()
                fun api(builder: Request.Builder): String? {
                    val resp = client.newCall(builder.addHeader("Authorization", "Bearer $token").build()).execute()
                    val body = resp.body?.string().orEmpty()
                    resp.close()
                    return if (resp.isSuccessful) body else null
                }
                val q = java.net.URLEncoder.encode(
                    "mimeType='application/vnd.google-apps.folder' and name='Al-Mustashar Cases' and trashed=false", "UTF-8"
                )
                var folderId: String? = null
                api(Request.Builder().url("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&spaces=drive").get())?.let {
                    try {
                        val arr = JSONObject(it).getJSONArray("files")
                        if (arr.length() > 0) folderId = arr.getJSONObject(0).getString("id")
                    } catch (_: Exception) {
                    }
                }
                if (folderId == null) {
                    val meta = JSONObject().apply {
                        put("name", "Al-Mustashar Cases")
                        put("mimeType", "application/vnd.google-apps.folder")
                    }
                    api(
                        Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id")
                            .post(okhttp3.RequestBody.create("application/json".toMediaType(), meta.toString()))
                    )?.let { folderId = JSONObject(it).getString("id") }
                }
                val fid = folderId ?: return false
                val meta = JSONObject().apply {
                    put("name", name)
                    put("parents", JSONArray().put(fid))
                }
                val body = MultipartBody.Builder()
                    .setType("multipart/related".toMediaType())
                    .addPart(
                        okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                        okhttp3.RequestBody.create("application/json".toMediaType(), meta.toString())
                    )
                    .addPart(
                        okhttp3.Headers.headersOf("Content-Type", "application/json"),
                        okhttp3.RequestBody.create("application/json".toMediaType(), bytes)
                    )
                    .build()
                api(
                    Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                        .post(body)
                ) != null
            } finally {
                try {
                    GoogleAuthUtil.clearToken(context, token)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
