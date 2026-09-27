// [context: Kotlin, Android API 26+, wipe APK data + uninstall]
package com.system.service.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.system.service.jni.NativeBridge
import kotlinx.coroutines.*

class SelfDestruct(private val context: Context) {

    suspend fun execute() = withContext(Dispatchers.IO) {
        try {
            NativeBridge.nativeWipe(context.filesDir.absolutePath)
            NativeBridge.nativeWipe(context.cacheDir.absolutePath)
            context.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().clear().commit()
            clearDatabases()
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
            launchUninstall()
        }
    }

    private fun clearDatabases() {
        context.databaseList().forEach { name ->
            context.deleteDatabase(name)
        }
    }

    private fun launchUninstall() {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
