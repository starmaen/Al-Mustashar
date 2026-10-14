package com.maen.almustashar

import android.app.Application
import android.content.ComponentCallbacks2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// Application داخلي: تهيئة خفيفة + مزامنة القوانين مرة واحدة عند الإقلاع
// بدل ما كانت تتم في كل onResume وتسبب تهنيق وإعادة تحميل على أجهزة 4GB.
class App : Application() {

    companion object {
        @Volatile
        var newLawsNotified: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        // مزامنة أولية خفيفة في الخلفية مرة واحدة لكل عملية (process)
        // حتى لو قتل النظام العملية ثم أعادها، ترجع من الكاش المحلي فوراً.
        GlobalScope.launch(Dispatchers.IO) {
            try {
                LawsLocalCache.syncIfNeeded(applicationContext)
            } catch (_: Exception) {
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // لا نمسح كاش القوانين هنا عمداً: مسحه هو سبب الإحساس بإعادة التحميل.
        // نكتفي بتلميح لجمع القمامة عند الضغط الشديد.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            System.gc()
        }
    }
}
