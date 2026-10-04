package com.maen.almustashar

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class App : Application() {

    private var checked = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (checked) return
                if (activity is LicenseActivity || activity is OwnerPanelActivity) {
                    checked = true
                    return
                }
                checked = true
                verifyAccess(activity)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun verifyAccess(activity: Activity) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            FirebaseFirestore.getInstance().collection("admins").document(user.uid).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) return@addOnSuccessListener
                    checkDeviceLicense(activity)
                }
                .addOnFailureListener {
                    checkDeviceLicense(activity)
                }
        } else {
            checkDeviceLicense(activity)
        }
    }

    private fun checkDeviceLicense(activity: Activity) {
        val deviceId = DeviceUtils.getDeviceId(activity)
        if (LicenseManager.isLicensedCached(activity)) {
            if (LicenseManager.needsRemoteRecheck(activity)) {
                LicenseManager.checkRemoteLicense(activity, deviceId) { valid ->
                    if (!valid) redirectToLicense(activity)
                }
            }
            return
        }
        LicenseManager.checkRemoteLicense(activity, deviceId) { valid ->
            if (!valid) redirectToLicense(activity)
        }
    }

    private fun redirectToLicense(activity: Activity) {
        val intent = Intent(activity, LicenseActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        activity.startActivity(intent)
    }
}
