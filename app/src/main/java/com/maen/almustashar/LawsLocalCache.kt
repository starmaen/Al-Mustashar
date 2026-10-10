package com.maen.almustashar

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// التخزين المحلي لنصوص القوانين (أقل من 1 ميغا): سرعة فورية + عمل دون إنترنت.
// المصدر نفسه الذي يستخدمه وضع Drive (GitHub Pages / jsDelivr)، وFirestore يبقى الأصل والاحتياط.
object LawsLocalCache {
    private const val PREFS = "laws_cache"
    private const val KEY_UPDATED = "index_updated"
    private const val KEY_TOTAL = "index_total"
    private const val KEY_LAST_CHECK = "last_remote_check"
    private const val MAX_AGE = 24 * 3600 * 1000L

    private val MIRRORS = listOf(
        "https://starmaen.github.io/Al-Mustashar/data/laws/",
        "https://cdn.jsdelivr.net/gh/starmaen/Al-Mustashar@main/data/laws/"
    )

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "laws").apply { mkdirs() }

    private fun encodeUrl(url: String): String {
        val sb = StringBuilder()
        for (ch in url) {
            if (ch.code in 0..127) sb.append(ch)
            else for (b in ch.toString().toByteArray(Charsets.UTF_8)) sb.append("%").append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun fetchText(ep: String): String? {
        for (base in MIRRORS) {
            try {
                val conn = URL(encodeUrl(base + ep)).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    return conn.inputStream.bufferedReader().readText()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // تحميل الفهرس والملفات الناقصة/المتغيرة فقط. يعيد true إن وُجدت نسخة صالحة.
    fun syncIfNeeded(ctx: Context): Boolean {
        return try {
            val d = dir(ctx)
            val indexText = fetchText("index.json")
            if (indexText != null) {
                val idx = JSONObject(indexText)
                val laws = idx.optJSONArray("laws") ?: org.json.JSONArray()
                for (i in 0 until laws.length()) {
                    val l = laws.getJSONObject(i)
                    val file = l.optString("file")
                    if (file.isEmpty()) continue
                    val local = File(d, file)
                    var need = !local.exists()
                    if (!need) {
                        try {
                            val lj = JSONObject(local.readText())
                            if (lj.optInt("articles_count", -1) != l.optInt("articles_count", -2)) need = true
                        } catch (_: Exception) {
                            need = true
                        }
                    }
                    if (need) {
                        val t = fetchText(file)
                        if (t != null) local.writeText(t)
                    }
                }
                File(d, "index.json").writeText(indexText)
                fetchText("_manifest.json")?.let { File(d, "_manifest.json").writeText(it) }
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_UPDATED, idx.optString("last_updated"))
                    .putInt(KEY_TOTAL, laws.length())
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .apply()
            }
            File(d, "index.json").exists()
        } catch (_: Exception) {
            File(dir(ctx), "index.json").exists()
        }
    }

    // تحميل حوض البحث المحلي (قوانين + مواد) أو null إن لا نسخة
    fun loadPool(ctx: Context): Pair<List<LawsRepository.LawMeta>, List<LawsRepository.ArticleMeta>>? {
        return try {
            val d = dir(ctx)
            val indexFile = File(d, "index.json")
            if (!indexFile.exists()) return null
            val idx = JSONObject(indexFile.readText())
            val lawsArr = idx.optJSONArray("laws") ?: return null
            val manifest = try {
                JSONObject(File(d, "_manifest.json").readText())
            } catch (_: Exception) {
                null
            }
            val laws = mutableListOf<LawsRepository.LawMeta>()
            val arts = mutableListOf<LawsRepository.ArticleMeta>()
            for (i in 0 until lawsArr.length()) {
                val l = lawsArr.getJSONObject(i)
                val id = l.optString("id")
                val file = l.optString("file")
                if (id.isEmpty() || file.isEmpty()) continue
                val lf = File(d, file)
                if (!lf.exists()) continue
                var driveUrl: String? = null
                if (manifest != null) {
                    val keys = manifest.keys()
                    while (keys.hasNext()) {
                        val info = manifest.optJSONObject(keys.next())
                        if (info != null && info.optString("law_id") == id) {
                            val did = info.optString("drive_id")
                            if (did.isNotEmpty()) driveUrl = "https://drive.google.com/file/d/$did/view"
                            break
                        }
                    }
                }
                laws.add(LawsRepository.LawMeta(id, l.optString("name", id), driveUrl))
                try {
                    val lj = JSONObject(lf.readText())
                    val arr = lj.optJSONArray("articles") ?: continue
                    for (j in 0 until arr.length()) {
                        val a = arr.getJSONObject(j)
                        val num = a.optInt("number", -1)
                        if (num < 0) continue
                        val text = a.optString("text")
                        if (text.isBlank()) continue
                        arts.add(
                            LawsRepository.ArticleMeta(
                                id, l.optString("name", id), num.toString(), text, driveUrl
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            if (laws.isEmpty() || arts.isEmpty()) null else Pair(laws, arts)
        } catch (_: Exception) {
            null
        }
    }

    // أسماء القوانين الجديدة عن النسخة المحفوظة (للتنبيه)، أو null في أول تشغيل/تعذر
    fun checkNewRemote(ctx: Context): List<String>? {
        return try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
            if (System.currentTimeMillis() - lastCheck < 6 * 3600 * 1000L && prefs.contains(KEY_TOTAL)) {
                return emptyList()
            }
            val indexText = fetchText("index.json") ?: return null
            val idx = JSONObject(indexText)
            val lawsArr = idx.optJSONArray("laws") ?: return null
            val d = dir(ctx)
            val knownIds = try {
                val local = JSONObject(File(d, "index.json").readText())
                val la = local.optJSONArray("laws") ?: org.json.JSONArray()
                (0 until la.length()).map { la.getJSONObject(it).optString("id") }.toSet()
            } catch (_: Exception) {
                null
            } ?: return null // أول تشغيل — يُخزَّن بصمت عبر syncIfNeeded
            val fresh = mutableListOf<String>()
            for (i in 0 until lawsArr.length()) {
                val l = lawsArr.getJSONObject(i)
                if (!knownIds.contains(l.optString("id"))) {
                    fresh.add(l.optString("name", l.optString("id")))
                }
            }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            fresh
        } catch (_: Exception) {
            null
        }
    }
}
