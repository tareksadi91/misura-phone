package com.misura.phone.hands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.util.Log
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.contactRank
import com.misura.core.agent.schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** Read-only access to the phone's contacts. No app launch or permission prompt. */
class Contacts(private val ctx: Context) : Tool {
    override val spec = ToolSpec("contacts", "Find up to 5 people by display name, with phone numbers (type labels) and email addresses. Exact names come first, then starts-with, then contains. Read-only.",
        schema("name" to "string", required = listOf("name")))
    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    override fun label(args: JsonObject) = "Finding ${args.s("name") ?: "contacts"}"
    override fun summary(result: JsonObject) = result.s("summary")
    override val app: String? by lazy {
        ctx.packageManager.resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS), 0)?.activityInfo?.packageName
    }

    override suspend fun run(args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext error("contacts permission not granted")
        }
        try {
            val name = requireNotNull(args.s("name")) { "name required" }
            // Treat LIKE wildcards as literal name characters.
            val escaped = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val found = linkedMapOf<Long, String>()
            checkNotNull(ctx.contentResolver.query(Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Data.DISPLAY_NAME),
                "${Data.DISPLAY_NAME} LIKE ? ESCAPE '\\'", arrayOf("%$escaped%"), null)).use { c ->
                while (c.moveToNext()) found[c.getLong(0)] = c.getString(1).orEmpty()
            }
            val people = found.entries.sortedWith(compareBy<Map.Entry<Long, String>> { contactRank(it.value, name) }
                .thenBy { it.value.lowercase(Locale.ROOT) }.thenBy { it.key }).take(5)
            buildJsonObject {
                put("people", buildJsonArray { people.forEach { add(person(it.key, it.value)) } })
                put("summary", if (people.size == 1) "1 person" else "${people.size} people")
            }
        } catch (e: SecurityException) {
            error("contacts permission not granted")
        } catch (e: IllegalArgumentException) {
            error(e.message ?: "invalid contacts arguments")
        } catch (e: RuntimeException) {
            if (e is java.util.concurrent.CancellationException) throw e
            Log.w("MisuraContacts", "contacts provider failed (${e.javaClass.simpleName})")
            error("contacts provider failed")
        }
    }

    private fun person(id: Long, name: String): JsonObject {
        val phones = linkedSetOf<JsonObject>()
        val emails = linkedSetOf<String>()
        checkNotNull(ctx.contentResolver.query(Data.CONTENT_URI,
            arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3),
            "${Data.CONTACT_ID}=? AND ${Data.MIMETYPE} IN (?, ?)",
            arrayOf(id.toString(), Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE), "${Data._ID} ASC")).use { c ->
            while (c.moveToNext()) {
                val value = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                when (c.getString(0)) {
                    Phone.CONTENT_ITEM_TYPE -> phones += buildJsonObject {
                        put("number", value)
                        put("type", Phone.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString())
                    }
                    Email.CONTENT_ITEM_TYPE -> emails += value
                }
            }
        }
        return buildJsonObject {
            put("name", name)
            put("phones", buildJsonArray { phones.forEach { add(it) } })
            put("emails", buildJsonArray { emails.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }
}
