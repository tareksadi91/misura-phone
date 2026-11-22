package com.misura.phone.store

import android.content.Context
import com.misura.phone.screen.SheetEntry
import org.json.JSONArray
import org.json.JSONObject

/** Ordered local pins and Misura's own successful launches; no usage-access permission. */
class Pins(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_sheet", Context.MODE_PRIVATE)

    fun entries(): List<SheetEntry> = runCatching {
        val array = JSONArray(prefs.getString("pins", "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching {
            val item = array.getJSONObject(i)
            SheetEntry(item.getString("pkg"), item.getString("name"),
                item.optString("shortcutId").takeIf { it.isNotEmpty() },
                item.optString("shortcutLabel").takeIf { it.isNotEmpty() }, item.optBoolean("made"))
        }.getOrNull() }.distinctBy { it.key }
    }.getOrDefault(emptyList())

    fun toggle(entry: SheetEntry) = synchronized(lock) {
        val old = entries()
        val next = if (old.any { it.key == entry.key }) old.filterNot { it.key == entry.key } else old + entry
        val array = JSONArray()
        next.forEach { pin -> array.put(JSONObject().apply {
            put("pkg", pin.pkg); put("name", pin.name); put("made", pin.made)
            pin.shortcutId?.let { put("shortcutId", it) }
            pin.shortcutLabel?.let { put("shortcutLabel", it) }
        }) }
        prefs.edit().putString("pins", array.toString()).apply()
    }

    fun launchCounts(): Map<String, Int> = prefs.all.mapNotNull { (key, value) ->
        if (key.startsWith("launch:") && value is Int) key.removePrefix("launch:") to value else null
    }.toMap()

    fun launched(pkg: String) = synchronized(lock) {
        val key = "launch:$pkg"
        val count = prefs.getInt(key, 0)
        prefs.edit().putInt(key, if (count == Int.MAX_VALUE) count else count + 1).apply()
    }

    private companion object { val lock = Any() }
}
