import re, os

# 1. تحديث activity_search.xml لإضافة زر PDF بجانب نسخ ومشاركة
layout_file = './app/src/main/res/layout/activity_search.xml'
with open(layout_file, 'r', encoding='utf-8') as f:
    xml = f.read()

if 'btnOpenPdf' not in xml:
    # تعديل عرض وتنسيق شريط الأزرار
    target = r'(<Button\s+android:id="@\+id/btnShareArticle"[\s\S]*?android:textColor="#FFFFFF"\s*/>)'
    replacement = r'''\1

        <Button
            android:id="@+id/btnOpenPdf"
            android:layout_width="0dp"
            android:layout_height="46dp"
            android:layout_weight="1"
            android:backgroundTint="#0284C7"
            android:text="📄 الأصل PDF"
            android:textColor="#FFFFFF" />'''
    xml = re.sub(target, replacement, xml)
    with open(layout_file, 'w', encoding='utf-8') as f:
        f.write(xml)
    print("✓ تم تثبيت زر الـ PDF في واجهة activity_search.xml")
else:
    print("✓ زر الـ PDF موجود مسبقاً في ملف الواجهة")

# 2. تحديث كود SearchActivity.kt لربط حدث النقر
kt_file = './app/src/main/java/com/maen/almustashar/SearchActivity.kt'
with open(kt_file, 'r', encoding='utf-8') as f:
    kt = f.read()

if 'import android.net.Uri' not in kt:
    kt = kt.replace('import android.content.Intent', 'import android.content.Intent\nimport android.net.Uri')

if 'btnOpenPdf' not in kt:
    target_kt = r'(findViewById<Button>\(R\.id\.btnShareArticle\)\.setOnClickListener\s*\{[\s\S]*?startActivity\(Intent\.createChooser\(intent,\s*"مشاركة المادة"\)\)\s*\}\s*\})'
    replacement_kt = r'''\1

        findViewById<Button>(R.id.btnOpenPdf).setOnClickListener {
            val text = tvSearchResult.text.toString()
            val urlMatcher = Regex("https://drive\\.google\\.com/[^\\s]+").find(text)
            val pdfUrl = urlMatcher?.value
            if (!pdfUrl.isNullOrBlank()) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(pdfUrl))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر فتح الرابط: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "ملف الـ PDF غير متوفر لهذه المادة حالياً", Toast.LENGTH_SHORT).show()
            }
        }'''
    kt = re.sub(target_kt, replacement_kt, kt)
    with open(kt_file, 'w', encoding='utf-8') as f:
        f.write(kt)
    print("✓ تم ربط زر الـ PDF برمجياً في SearchActivity.kt")
else:
    print("✓ كود زر الـ PDF مضاف مسبقاً في SearchActivity.kt")

