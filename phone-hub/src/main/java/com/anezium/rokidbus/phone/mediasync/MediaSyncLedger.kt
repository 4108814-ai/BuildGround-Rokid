package com.anezium.rokidbus.phone.mediasync

import android.content.Context
import com.anezium.rokidbus.shared.MediaSyncItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * One capture that has been written to the phone gallery. Identity is `name + size`: the glasses
 * never reuse a capture filename, and the size guards against a truncated first attempt being
 * mistaken for a completed one.
 */
data class SyncLedgerEntry(
    val name: String,
    val sizeBytes: Long,
    val syncedAtMillis: Long,
)

/**
 * The phone-side record of what photo sync has already delivered.
 *
 * It is deliberately authoritative over the gallery: a capture the wearer later deletes from
 * Photos must not come back on the next sync. Only a hub-side reset clears it.
 */
interface SyncLedgerStorage {
    fun read(): String?
    fun write(value: String)
}

class SyncLedger(private val storage: SyncLedgerStorage) {
    private val entries = LinkedHashMap<String, SyncLedgerEntry>()
    private var loaded = false

    @Synchronized
    fun snapshot(): List<SyncLedgerEntry> {
        ensureLoaded()
        return entries.values.toList()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return entries.size
    }

    @Synchronized
    fun contains(item: MediaSyncItem): Boolean {
        ensureLoaded()
        val entry = entries[item.name] ?: return false
        return entry.sizeBytes == item.sizeBytes
    }

    /** Catalog entries that still need to travel, in catalog order (oldest capture first). */
    @Synchronized
    fun pending(catalog: List<MediaSyncItem>): List<MediaSyncItem> {
        ensureLoaded()
        return catalog.filterNot(::contains)
    }

    @Synchronized
    fun record(item: MediaSyncItem, syncedAtMillis: Long) {
        ensureLoaded()
        entries[item.name] = SyncLedgerEntry(item.name, item.sizeBytes, syncedAtMillis)
        persist()
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        entries.clear()
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = storage.read().orEmpty()
        if (raw.isBlank()) return
        runCatching { SyncLedgerCodec.decode(raw) }
            .getOrDefault(emptyList())
            .forEach { entries[it.name] = it }
    }

    private fun persist() {
        storage.write(SyncLedgerCodec.encode(entries.values))
    }

    companion object {
        const val MAX_ENTRIES = 20_000
    }
}

object SyncLedgerCodec {
    const val VERSION = 1

    fun encode(entries: Collection<SyncLedgerEntry>): String {
        val retained = if (entries.size > SyncLedger.MAX_ENTRIES) {
            entries.toList().takeLast(SyncLedger.MAX_ENTRIES)
        } else {
            entries
        }
        return JSONObject()
            .put("version", VERSION)
            .put(
                "entries",
                JSONArray().apply {
                    retained.forEach { entry ->
                        put(
                            JSONObject()
                                .put("name", entry.name)
                                .put("size", entry.sizeBytes)
                                .put("at", entry.syncedAtMillis),
                        )
                    }
                },
            )
            .toString()
    }

    fun decode(raw: String): List<SyncLedgerEntry> {
        val payload = JSONObject(raw)
        if (payload.optInt("version") != VERSION) return emptyList()
        val array = payload.optJSONArray("entries") ?: return emptyList()
        val entries = ArrayList<SyncLedgerEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name")
            if (name.isBlank()) continue
            entries += SyncLedgerEntry(
                name = name,
                sizeBytes = item.optLong("size", -1L),
                syncedAtMillis = item.optLong("at"),
            )
        }
        return entries
    }
}

class SharedPreferencesSyncLedgerStorage(context: Context) : SyncLedgerStorage {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY_ENTRIES, null)

    override fun write(value: String) {
        preferences.edit().putString(KEY_ENTRIES, value).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "nexus_media_sync"
        const val KEY_ENTRIES = "ledger_v1"
    }
}
