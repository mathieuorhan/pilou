package fr.priseok.app

import android.content.Context
import android.content.SharedPreferences
import fr.priseok.app.core.Contact
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

    fun settings(): Settings {
        prefs.getString("contacts", null)?.let { raw ->
            return Settings(Codec.meds(prefs.getString("meds", null) ?: "[]"), Codec.contacts(raw))
        }
        return legacySettings()
    }

    /**
     * Versions 1 and 2 had one contact and one delay for everything. Version 1 also had a single
     * reminder, stored as separate keys. Both become the current format: contact #1, alerting
     * every medication that had the alert on, with the old delay.
     */
    private fun legacySettings(): Settings {
        val phone = prefs.getString("phone", "") ?: ""
        val contact = if (phone.isBlank()) null else Contact(1L, prefs.getString("contactName", "") ?: "", phone)
        val delay = prefs.getInt("delayMinutes", 120)
        val meds = prefs.getString("meds", null)?.let { Codec.meds(it) } ?: version1Med()
        return Settings(
            meds.map { m -> m.copy(delayMinutes = delay, contactIds = if (m.contactIds.isNotEmpty() && contact != null) setOf(1L) else emptySet()) },
            listOfNotNull(contact),
        )
    }

    private fun version1Med(): List<Med> {
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
                contactIds = setOf(1L), // version 1 always alerted
                activeSince = since,
            )
        )
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("meds", Codec.meds(s.meds))
            .putString("contacts", Codec.contacts(s.contacts))
            .remove("hour").remove("minute").remove("frequency").remove("dayOfWeek").remove("dayOfMonth")
            .remove("activeSince").remove("delayMinutes").remove("contactName").remove("phone")
            .commit()
    }

    fun history(): Map<Long, Entry> {
        val raw = prefs.getString("history", null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate { i -> Codec.entry(arr.getJSONObject(i)).let { it.cycle to it } }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun update(cycle: Long, change: (Entry) -> Entry?) {
        val h = history().toMutableMap()
        val next = change(h[cycle] ?: Entry(cycle))
        if (next == null) h.remove(cycle) else h[cycle] = next
        val arr = JSONArray()
        h.values.sortedBy { it.cycle }.takeLast(KEEP).forEach { arr.put(Codec.entry(it)) }
        // commit, not apply: receivers may be killed right after returning
        prefs.edit().putString("history", arr.toString()).commit()
    }

    /** Confirms the whole dose. */
    fun markTaken(cycle: Long, at: Long) = update(cycle) { if (it.takenAt == null) it.copy(takenAt = at) else it }

    /** Confirms one medication of the dose. */
    fun markMedTaken(cycle: Long, medId: Long, at: Long) =
        update(cycle) { if (it.isTaken(medId)) it else it.copy(medsTaken = it.medsTaken + (medId to at)) }

    /** Puts an entry back as it was before a confirmation (undo). Null = there was none. */
    fun restore(cycle: Long, before: Entry?) = update(cycle) { before }

    /** Remembers the entry as it is before a confirmation, so it can be undone. */
    fun saveUndo(cycle: Long) {
        val before = history()[cycle]
        val edit = prefs.edit()
        // Only the latest confirmation can be undone: drop older snapshots.
        prefs.all.keys.filter { it.startsWith("undo_") }.forEach { edit.remove(it) }
        edit.putString("undo_$cycle", before?.let { Codec.entry(it).toString() } ?: "").commit()
    }

    /** Undoes the last confirmation of [cycle]. Does nothing if there is nothing to undo. */
    fun undo(cycle: Long) {
        val raw = prefs.getString("undo_$cycle", null) ?: return
        restore(cycle, raw.takeIf { it.isNotEmpty() }?.let { Codec.entry(JSONObject(it)) })
        prefs.edit().remove("undo_$cycle").commit()
    }

    fun recordSms(cycle: Long, contactId: Long, at: Long) = update(cycle) { it.copy(sms = it.sms + (contactId to at)) }

    fun recordSmsFailed(cycle: Long, contactId: Long) = update(cycle) { it.copy(smsFailed = it.smsFailed + contactId) }

    // Several doses a day: keep about four months of them.
    companion object { private const val KEEP = 500 }
}

/** JSON for storage, and for keeping settings drafts across rotations. */
object Codec {
    fun meds(list: List<Med>): String = JSONArray().apply { list.forEach { put(med(it)) } }.toString()

    fun meds(raw: String): List<Med> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { med(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun med(m: Med) = JSONObject().apply {
        put("id", m.id)
        put("n", m.name)
        put("t", JSONArray(m.times))
        put("f", m.frequency.name)
        put("w", JSONArray(m.daysOfWeek.sorted()))
        put("d", m.dayOfMonth)
        put("l", m.delayMinutes)
        put("k", JSONArray(m.contactIds.sorted()))
        m.endDate?.let { put("e", it.toEpochDay()) }
        if (m.paused) put("p", true)
        put("s", m.activeSince)
    }

    private fun med(o: JSONObject): Med {
        val t = o.getJSONArray("t")
        val w = o.getJSONArray("w")
        return Med(
            id = o.getLong("id"),
            name = o.optString("n", ""),
            times = (0 until t.length()).map { t.getInt(it) }.ifEmpty { listOf(9 * 60) },
            frequency = runCatching { Frequency.valueOf(o.getString("f")) }.getOrDefault(Frequency.DAILY),
            daysOfWeek = (0 until w.length()).map { w.getInt(it) }.toSet().ifEmpty { setOf(1) },
            dayOfMonth = o.optInt("d", 1),
            delayMinutes = o.optInt("l", 120),
            contactIds = o.optJSONArray("k")?.let { k -> (0 until k.length()).map { k.getLong(it) }.toSet() }
                // Version 2 had an alert switch instead of contacts: keep "on" as a marker, mapped to contact #1.
                ?: if (o.optBoolean("a", true)) setOf(1L) else emptySet(),
            endDate = if (o.has("e")) LocalDate.ofEpochDay(o.getLong("e")) else null,
            paused = o.optBoolean("p", false),
            activeSince = o.optLong("s", 0L),
        )
    }

    fun contacts(list: List<Contact>): String = JSONArray().apply {
        list.forEach { c -> put(JSONObject().apply { put("id", c.id); put("n", c.name); put("p", c.phone) }) }
    }.toString()

    fun contacts(raw: String): List<Contact> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Contact(o.getLong("id"), o.optString("n", ""), o.optString("p", ""))
        }
    }.getOrDefault(emptyList())

    fun entry(e: Entry) = JSONObject().apply {
        put("c", e.cycle)
        e.takenAt?.let { put("t", it) }
        if (e.medsTaken.isNotEmpty()) put("m", JSONObject().apply { e.medsTaken.forEach { (k, v) -> put(k.toString(), v) } })
        if (e.sms.isNotEmpty()) put("s", JSONObject().apply { e.sms.forEach { (k, v) -> put(k.toString(), v) } })
        if (e.smsFailed.isNotEmpty()) put("f", JSONArray(e.smsFailed.sorted()))
    }

    fun entry(o: JSONObject): Entry {
        fun map(key: String): Map<Long, Long> = o.optJSONObject(key)?.let { m ->
            m.keys().asSequence().associate { it.toLong() to m.getLong(it) }
        } ?: emptyMap()
        // Versions 1 and 2 stored one text time ("s") and one failure flag ("f"), both for contact #1.
        val sms = if (o.optJSONObject("s") != null) map("s") else if (o.has("s")) mapOf(1L to o.getLong("s")) else emptyMap()
        val failed = o.optJSONArray("f")?.let { f -> (0 until f.length()).map { f.getLong(it) }.toSet() }
            ?: if (o.optBoolean("f", false)) setOf(1L) else emptySet()
        return Entry(
            cycle = o.getLong("c"),
            takenAt = if (o.has("t")) o.getLong("t") else null,
            medsTaken = map("m"),
            sms = sms,
            smsFailed = failed,
        )
    }
}
