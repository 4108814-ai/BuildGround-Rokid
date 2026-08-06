package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import java.util.concurrent.CopyOnWriteArrayList

internal object HudTopInset {
    private const val PREFS = "hud_position"
    private const val KEY_TOP_INSET_DP = "top_inset_dp"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    @Volatile private var initialized = false
    @Volatile private var currentInsetDp = PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP

    fun current(context: Context): Int = synchronized(lock) {
        ensureLoaded(context.applicationContext)
        currentInsetDp
    }

    fun set(context: Context, value: Int) {
        val appContext = context.applicationContext
        val cleanValue = sanitize(value)
        val changed = synchronized(lock) {
            ensureLoaded(appContext)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_TOP_INSET_DP, cleanValue)
                .apply()
            if (currentInsetDp == cleanValue) {
                false
            } else {
                currentInsetDp = cleanValue
                true
            }
        }
        if (changed) notifyListeners(cleanValue)
    }

    fun restore(context: Context): Int {
        val next = readPersisted(context.applicationContext)
        val changed = synchronized(lock) {
            val wasInitialized = initialized
            val previous = currentInsetDp
            currentInsetDp = next
            initialized = true
            wasInitialized && previous != next
        }
        if (changed) notifyListeners(next)
        return next
    }

    fun observe(context: Context, listener: (Int) -> Unit): () -> Unit {
        val initial = synchronized(lock) {
            ensureLoaded(context.applicationContext)
            listeners.add(listener)
            currentInsetDp
        }
        dispatch { if (listener in listeners) listener(initial) }
        return { listeners.remove(listener) }
    }

    fun sanitize(value: Int): Int = PhoneHubCapabilitiesContract.sanitizeHudTopInsetDp(value)

    private fun ensureLoaded(context: Context) {
        if (initialized) return
        currentInsetDp = readPersisted(context)
        initialized = true
    }

    private fun readPersisted(context: Context): Int {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = runCatching {
            preferences.getInt(
                KEY_TOP_INSET_DP,
                PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
            )
        }.getOrNull()
        val cleanValue = sanitize(
            stored ?: PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
        )
        if (stored == null || stored != cleanValue) {
            preferences.edit().putInt(KEY_TOP_INSET_DP, cleanValue).apply()
        }
        return cleanValue
    }

    private fun notifyListeners(value: Int) {
        dispatch {
            listeners.forEach { listener ->
                runCatching { listener(value) }
            }
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            main.post(action)
        }
    }
}
