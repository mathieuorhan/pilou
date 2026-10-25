package fr.priseok.app

import android.content.Context
import android.content.SharedPreferences
import fr.priseok.app.core.Entry
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Med
import fr.priseok.app.core.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Settings and dose history, kept in SharedPreferences. Small data, so no database. */
class Store(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("priseok", Context.MODE_PRIVATE)

    fun settings(): Settings = Settings(
        meds = prefs.getString("meds", null)?.let { Codec.meds(it) } ?: migratedMed(),
        delayMinutes = prefs.getInt("delayMinutes", 120),
        contactName = prefs.getString("contactName", "") ?: "",
        phone = prefs.getString("phone", "") ?: "",
    )

    /** Version 1 had a single reminder, stored as separate keys: it becomes one unnamed medication. */
    private fun migratedMed(): List<Med> {
        val since = prefs.getLong("activeSince", 0L)
        if (since <= 0L) return emptyList()
        val freq = runCatching { Frequency.valueOf(prefs.getString("frequency", null)!!) }.getOrDefault(Frequency.DAILY)
        return listOf(
            Med(
                id = 1L,
                times = listOf(prefs.getInt("hour", 9) * 60 + prefs.getInt("minute", 0)),
                frequency = freq,
                daysOfWeek = setOf(prefs.getInt("dayOfWeek", 1)),
                dayOfMonth = prefs.getInt("dayOfMonth", 1),
                activeSince = since,
            )
        )
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("meds", Codec.meds(s.meds))
            .putInt("delayMinutes", s.delayMinutes)
            .putString("contactName", s.contactName)
            .putString("phone", s.phone)
            .remove("hour").remove("minute").remove("frequency")
            .remove("dayOfWeek").remove("dayOfMonth").remove("activeSince")
            .commit()
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

    // Several doses a day: keep about four months of them.
    companion object { private const val KEEP = 500 }
}

/** The medication list as JSON, for storage and for keeping a settings draft across rotations. */
object Codec {
    fun meds(list: List<Med>): String = JSONArray().apply {
        list.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id)
                put("n", m.name)
                put("t", JSONArray(m.times))
                put("f", m.frequency.name)
                put("w", JSONArray(m.daysOfWeek.sorted()))
                put("d", m.dayOfMonth)
                put("a", m.alert)
                m.endDate?.let { put("e", it.toEpochDay()) }
                put("s", m.activeSince)
            })
        }
    }.toString()

    fun meds(raw: String): List<Med> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.getJSONArray("t")
            val w = o.getJSONArray("w")
            Med(
                id = o.getLong("id"),
                name = o.optString("n", ""),
                times = (0 until t.length()).map { t.getInt(it) }.ifEmpty { listOf(9 * 60) },
                frequency = runCatching { Frequency.valueOf(o.getString("f")) }.getOrDefault(Frequency.DAILY),
                daysOfWeek = (0 until w.length()).map { w.getInt(it) }.toSet().ifEmpty { setOf(1) },
                dayOfMonth = o.optInt("d", 1),
                alert = o.optBoolean("a", true),
                endDate = if (o.has("e")) LocalDate.ofEpochDay(o.getLong("e")) else null,
                activeSince = o.optLong("s", 0L),
            )
        }
    }.getOrDefault(emptyList())
}
