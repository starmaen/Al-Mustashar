# دليل مشروع المستشار — المرجع الشامل

هذا المجلد يوثّق كل طريقة عمل متبعة بمشروع "المستشار" من أوله، بحيث
يقدر أي شخص أو أداة (مو بالضرورة محادثة Claude سابقة) يكمل العمل من
الصفر بالاعتماد على هذا الدليل فقط.

## 1) الحسابات والروابط الأساسية

- GitHub: `https://github.com/starmaen/Al-Mustashar` (حساب star1741965@gmail.com)
- Firebase Console: `https://console.firebase.google.com/u/0/project/al-mustashar-7f6b7/overview`
- Google Cloud Console (نفس المشروع): بدّل `al-mustashar-7f6b7` بمعرف المشروع بأي رابط `console.cloud.google.com`
- Google Drive + Firebase Auth (المالك): starsyria2500@gmail.com — مساحة 5 تيرا
- بيئة التطوير: Termux على الهاتف (لا يوجد Android SDK محليًا، فالبناء الفعلي يتم حصرًا عبر GitHub Actions)
- مجلد العمل المحلي: `~/mustashar` (أدوات معالجة القوانين) و `~/Al-Mustashar` (كود تطبيق أندرويد، مستنسخ من GitHub)

## 2) هيكلة قاعدة البيانات (Firestore)

```
laws/{lawId}                  مثال: civil_code, personal_status, penal_code
    name: "اسم القانون الكامل"
    category: "تصنيف"
    year: 1949
    articles/{articleNumber}  المعرف نص الرقم، أو "<رقم>_bis" لمادة "مكرر"
        number: <رقم>
        text: "نص المادة"
        keywords: [كلمات مفتاحية]

cases/{caseId}                 أرشيف قضايا المحامين
    clientName, court, date, procedures, sessions, summary, title
    userId: <معرف Firebase Auth>        ← أساس عزل الخصوصية
    driveFileId: (اختياري، مستقبلاً)     ← مؤشر لمرفق على Drive

queries/{queryId}              سجل الاستشارات
    email, question, timestamp, userId
```

قواعد الأمان الحالية (Firestore Rules): `laws` للقراءة فقط للمسجّلين
دخول، `cases` و`queries` بعزل كامل بحسب `userId` (كل مستخدم يرى بياناته
فقط). نسخة القواعد الكاملة محفوظة بتاريخ آخر نشر على Firebase Console
نفسه (تبويب Rules).

## 3) أداة معالجة ورفع القوانين — `tools.js`

ملف واحد موحّد بمجلد `~/mustashar/tools.js`، أوامره:

```bash
node tools.js parse-law <file.txt> --id <معرف> --name "<اسم>" --category <تصنيف> --year <سنة> [--dry-run]
node tools.js fix-arabic <in.txt> <out.txt> [fixes.json]
node tools.js add-note <lawId> <رقم المادة> "<نص>"
node tools.js check-law <lawId>
```

### خط المعالجة القياسي لقانون جديد (من ملف PDF):

```bash
pdftotext -layout law.pdf law.txt        # أو بدون -layout إذا كان القانون بعمودين
node tools.js fix-arabic law.txt law_fixed.txt
node tools.js parse-law law_fixed.txt --id <معرف> --name "<اسم>" --category <تصنيف> --year <سنة> --dry-run
# تحقق من عدد المواد والتكرار والنواقص قبل إزالة --dry-run
node tools.js check-law <معرف>
# إذا كان كل شيء سليم، أعد الأمر بدون --dry-run للرفع الفعلي
```

### مشاكل شائعة بالاستخراج وحلولها المجرَّبة:

- **حروف عربية معكوسة** (مثل "ال" بدل "لا"): يعالجها `fix-arabic` تلقائيًا (قواعد نصية + كلمات معروفة).
- **حروف "تقديمية" Unicode مشوّهة** (ﻗﺎﻨﻭﻥ بدل قانون، من ملفات PDF قديمة بخط Acrobat Distiller): يعالجها `fix-arabic` أيضًا عبر تطبيع NFKC.
- **تكرار كبير بعدد "المواد المستخرجة" مع تركّز التكرار برقمين فقط**: غالبًا استشهادات برقم مادة داخل نص مواد أخرى (مثل "وفقاً للمادة 1") يُساء فهمها كترويسة جديدة. الحل: نمط `articlePattern` بالأداة يشترط أن تقف الترويسة وحدها بالسطر.
- **تنسيق عمودين (Two-column PDF)**: قد يُنتج ترقيمًا عشوائيًا بـ `pdftotext -layout`؛ جرّب بدون `-layout` أو العكس، وقارن عدد المواد الناتج بالعدد الحقيقي المعروف للقانون.
- **أفضل مصدر نصوص لحد الآن**: موقع **مجلس الشعب السوري** (nearest to: majles.gov.sy) يعطي نصًا نظيفًا تمامًا بدون أي تشويه، ويُفضَّل عن أي PDF آخر إن توفر القانون فيه.

### القوانين المرفوعة حاليًا (حدّث هذا القسم عند إضافة قانون):

- القانون المدني السوري (`civil_code`) — 1105 مادة، 57–81 ملغاة
- قانون الأحوال الشخصية (`personal_status`) — 308 مادة + 305 مكرر، شمل تعديل القانون 4/2019
- قانون العقوبات (`penal_code`) — قيد الإكمال
- قانون أصول المحاكمات الجزائية (112/1950) — قيد الإضافة (مصدر نظيف من مجلس الشعب)
- قانون تنظيم مهنة المحاماة (30/2010) — مرفوع، نصه يحتاج تدقيق (تشوه بتبديل ترتيب حروف داخل الكلمات)

## 4) بنية البحث القانوني بالتطبيق — `LawsRepository.kt`

الملف: `app/src/main/java/com/maen/almustashar/LawsRepository.kt`

يدعم 3 مستويات بحث بدالة واحدة `searchRelevantLaws(question)`:
1. رقم مادة + اسم قانون محدد بالسؤال (`detectLawId`) → يرجع من ذلك القانون فقط.
2. رقم مادة بدون تحديد قانون → يرجع من كل قانون فيه هذا الرقم.
3. لا يوجد رقم → بحث بالمعنى عبر تسجيل نقاط (keyword scoring) على كل المواد المخزَّنة.

عند إضافة قانون جديد، أضف اسمه المختصر لدالة `detectLawId` حتى يتعرف
عليه البحث المحدد.

ملفات الاستهلاك: `AIClient.kt` (الاستشارة السريعة عبر `askLegalQuestion`)
و`SearchActivity.kt` (شاشة البحث المباشر) و`GeneralSearchActivity.kt`
(يجب أن تستدعي `askLegalQuestion` أيضًا، لا `askGeneralQuestion` القديمة).

## 5) البناء والنشر

لا يوجد Android SDK على Termux، فالبناء المحلي بـ `gradlew` يفشل دومًا
بخطأ "SDK location not found" — **هذا متوقع وليس خطأ بالكود**. البناء
الفعلي الوحيد عبر GitHub Actions:

```bash
git add -A && git commit -m "<وصف التغيير>"
git push
```

ثم من GitHub → تبويب Actions → تابع آخر عملية بناء → حمّل الـ APK من
Artifacts إذا نجحت (✅)، أو افتح تفاصيل الفشل (❌) لرؤية رسالة الخطأ.

Workflow الوحيد المعتمد: `.github/workflows/build.yml`، يبني
`assembleDebug` باستخدام أسرار `GEMINI_API_KEY1` و`GROQ_API_KEY1`
المخزَّنة بإعدادات الريبو (Settings → Secrets).

## 6) أمان ومفاتيح حساسة

- `serviceAccountKey.json` (مفتاح خدمة Firebase الإداري): **لا يُرفع
  لـ Git إطلاقًا** (موجود بـ `.gitignore`)، يبقى محليًا فقط بـ
  `~/mustashar/serviceAccountKey.json`، ويُستخدم أيضًا لـ Drive (راجع
  القسم التالي).
- `app/al-mustashar.jks` (مفتاح توقيع التطبيق): **لسا مرفوع على GitHub
  من إعداد سابق** — هذا غير آمن إذا كان الريبو عامًا. يحتاج نقلًا
  لـ GitHub Secrets ومفتاحًا جديدًا (مهمة لم تُنجز بعد، راجع القسم 8).
- قواعد Firestore يجب أن تبقى محصورة بالمصادقة (`request.auth != null`)
  وبعزل `userId` لأي مجموعة فيها بيانات شخصية.

## 7) ربط Google Drive (مجلدان داخل مجلد التطبيق)

**المبدأ:** نصوص القوانين تبقى في Firestore فقط دون أي تغيير. Drive
يُستخدم حصرًا لملفات PDF الأصلية للقوانين (أرشيف مرجعي) ومرفقات قضايا
المستخدمين. Firestore يحتفظ فقط بـ `driveFileId` كمؤشر.

### المجلدان المعتمدان:

1. **مجلد القوانين** (البحث القانوني عبر Drive/GitHub فقط):
   `https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3`
   - تضع فيه ملف PDF → سكربت GitHub كل 6 ساعات ينقحه لغويًا
     ويضعه JSON في `data/laws/` → يظهر في البحث (وضع Drive).
   - المعرّف مضبوط في `ocr_worker.py` عبر Secret ‏`GDRIVE_FOLDER_ID`
     وفي `functions/index.js` ‏(`LAWS_FOLDER_ID`) وفي `SearchActivity`
     (وضع Drive).
2. **مجلد الأرشيف الاحتياطي Secure PDFs** (مرتبط بـ Firebase):
   `https://drive.google.com/drive/folders/1Dl0H-rKSCTbO5ZMs2U4Ws_t7Lsc55Dr9`
   - المعرّف مضبوط في `functions/index.js` ‏(`ARCHIVE_FOLDER_ID`)،
     ودالة `searchDriveLaws` تقبل `scope=archive` للبحث فيه.

### الإعداد (تم تنفيذه):

1. فُعِّل Drive API لمشروع `al-mustashar-7f6b7`.
2. أُنشئ مجلد Drive باسم مناسب بحساب `starsyria2500@gmail.com`.
3. شُورك المجلد (صلاحية **محرِّر/Editor**) مع إيميل حساب الخدمة:
   `firebase-adminsdk-fbsvc@al-mustashar-7f6b7.iam.gserviceaccount.com`
   (نفس الحساب المستخدم لكل عمليات Firestore — لا اعتماد جديد).
4. مُعرّف المجلد مضبوط داخل `drive-tools.js` بمتغيّر `ROOT_FOLDER_ID`.

### الأداة — `drive-tools.js` (بجانب `tools.js` بنفس `~/mustashar`):

```bash
npm install googleapis   # مرة واحدة فقط

node drive-tools.js upload <ملف محلي> [مجلد_فرعي]
node drive-tools.js list [مجلد_فرعي]
node drive-tools.js download <fileId> <مسار_الحفظ>
node drive-tools.js delete <fileId>
```

مثال لرفع مرفق قضية مستخدم معين:
```bash
node drive-tools.js upload contract.pdf cases/<userUid>
```

### الربط بالتطبيق (خطوة قادمة، لم تُنفَّذ بعد):

التطبيق لا يتصل بـ Drive مباشرة (حماية لمفتاح الخدمة). الرفع يجب أن
يمر عبر خادم وسيط (Cloud Function، أو هذا السكربت مُدارًا يدويًا من
Termux حاليًا). عند الرفع، يُحفظ `driveFileId` الناتج داخل مستند
القضية بـ Firestore.

## 8) المهام المتبقية (لم تُنجز بعد، بترتيب الأولوية المتفق عليه سابقًا)

1. ربط رفع مرفقات القضايا بالتطبيق فعليًا عبر Drive (الكود الحالي
   للتطبيق لا يستدعي `drive-tools.js` أو أي مكافئ له بعد).
2. مفتاح توقيع جديد للتطبيق (`al-mustashar.jks`) محفوظ بـ GitHub
   Secrets بدل التواجد داخل الريبو.
3. نظام ترخيص وأكواد تفعيل للمستخدمين (خطة Blaze مطلوبة لاحقًا إذا
   اعتُمدت Cloud Functions لهذا الغرض).
4. إكمال رفع قانون العقوبات (مصدر نظيف محدَّد: alkarama.org) وقانون
   أصول المحاكمات الجزائية (مصدر نظيف: مجلس الشعب السوري) وتدقيق نص
   قانون تنظيم مهنة المحاماة المشوَّه حاليًا.
5. زر إظهار/إخفاء كلمة المرور في شاشة التسجيل (أُنجز سابقًا في شاشة
   الدخول فقط عبر `app:endIconMode="password_toggle"` على
   `TextInputLayout`؛ لم يُؤكَّد تطبيقه على شاشة التسجيل).
