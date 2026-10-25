package fr.priseok.app

import android.content.Context
import android.content.SharedPreferences
import fr.priseok.app.core.Entry
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Settings and dose history, kept in SharedPreferences. Small data, so no database. */
class Store(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("priseok", Context.MODE_PRIVATE)

    fun settings(): Settings = Settings(
        hour = prefs.getInt("hour", 9),
        minute = prefs.getInt("minute", 0),
        frequency = runCatching { Frequency.valueOf(prefs.getString("frequency", null)!!) }
            .getOrDefault(Frequency.DAILY),
        dayOfWeek = prefs.getInt("dayOfWeek", 1),
        dayOfMonth = prefs.getInt("dayOfMonth", 1),
        delayMinutes = prefs.getInt("delayMinutes", 120),
        contactName = prefs.getString("contactName", "") ?: "",
        phone = prefs.getString("phone", "") ?: "",
        activeSince = prefs.getLong("activeSince", 0L),
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putInt("hour", s.hour)
            .putInt("minute", s.minute)
            .putString("frequency", s.frequency.name)
            .putInt("dayOfWeek", s.dayOfWeek)
            .putInt("dayOfMonth", s.dayOfMonth)
            .putInt("delayMinutes", s.delayMinutes)
            .putString("contactName", s.contactName)
            .putString("phone", s.phone)
            .putLong("activeSince", s.activeSince)
            .apply()
    }

    fun history(): Map<Long, Entry> {
        val raw = prefs.getString("history", null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                val e = Entry(
                    cycle = o.getLong("c"),
                    takenAt = if (o.has("t")) o.getLong("t") else null,
                    smsAt = if (o.has("s")) o.getLong("s") else null,
                    smsFailed = o.optBoolean("f", false),
                )
                e.cycle to e
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun update(cycle: Long, change: (Entry) -> Entry) {
        val h = history().toMutableMap()
        h[cycle] = change(h[cycle] ?: Entry(cycle))
        val arr = JSONArray()
        h.values.sortedBy { it.cycle }.takeLast(KEEP).forEach { e ->
            arr.put(JSONObject().apply {
                put("c", e.cycle)
                e.takenAt?.let { put("t", it) }
                e.smsAt?.let { put("s", it) }
                if (e.smsFailed) put("f", true)
            })
        }
        // commit, not apply: receivers may be killed right after returning
        prefs.edit().putString("history", arr.toString()).commit()
    }

    fun markTaken(cycle: Long, at: Long) = update(cycle) { if (it.takenAt == null) it.copy(takenAt = at) else it }

    companion object { private const val KEEP = 120 }
}
