package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anezium.rokidbus.ink.InkWireLimits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** DUMP-protected, debug-build-only bridge from pushed wire files to the real surface path. */
class DebugInkBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val meter = intent.getBooleanExtra(EXTRA_METER, false)
        if (mode == MODE_HIDE) {
            SurfaceController.setInkResyncListener(null)
            SurfaceController.handleSurfaceEnvelope(
                context.applicationContext,
                BusEnvelope(
                    path = BusPaths.SURFACE_HIDE,
                    payload = JSONObject()
                        .put("surfaceId", SURFACE_ID)
                        .put("seq", nextSeq()),
                ),
            )
            return
        }
        if (mode != MODE_SHOW && mode != MODE_PATCH) {
            log("DEBUG_INK rejected unknown mode='$mode'")
            return
        }

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val pending = goAsync()
        Thread({
            try {
                val raw = readWireFile(path) ?: return@Thread
                val payloadName = if (mode == MODE_SHOW) "document" else "patch"
                SurfaceController.setInkResyncListener { request ->
                    log(
                        "DEBUG_INK resync requested current=${request.currentDocumentId}@" +
                            "${request.currentRevision}; re-push a full document before patching " +
                            "${request.patchDocumentId}@${request.patchBaseRevision}",
                    )
                }
                SurfaceController.handleSurfaceEnvelope(
                    context.applicationContext,
                    BusEnvelope(
                        path = if (mode == MODE_SHOW) {
                            BusPaths.SURFACE_SHOW
                        } else {
                            BusPaths.SURFACE_UPDATE
                        },
                        payload = JSONObject()
                            .put("surfaceId", SURFACE_ID)
                            .put("seq", nextSeq())
                            .put("kind", NexusSurface.KIND_INK)
                            .put("contentKey", CONTENT_KEY)
                            .put("handlesBack", false)
                            .put(
                                "ink",
                                JSONObject()
                                    .put(payloadName, raw)
                                    .put("debugActions", true)
                                    .put("debugFrameMeter", meter),
                            ),
                    ),
                )
            } catch (error: Exception) {
                logError("DEBUG_INK failed", error)
            } finally {
                pending.finish()
            }
        }, "RokidNexusDebugInk").apply { isDaemon = true }.start()
    }

    private fun readWireFile(path: String): String? {
        if (path.isBlank()) {
            log("DEBUG_INK rejected empty path")
            return null
        }
        val file = File(path)
        if (!file.isFile) {
            log("DEBUG_INK file not found path=$path")
            return null
        }
        if (file.length() > InkWireLimits.MAX_DOCUMENT_BYTES) {
            log("DEBUG_INK file exceeds ${InkWireLimits.MAX_DOCUMENT_BYTES} bytes path=$path")
            return null
        }
        return file.readText(Charsets.UTF_8)
    }

    private companion object {
        const val ACTION = "com.anezium.rokidbus.glasses.DEBUG_INK"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PATH = "path"
        const val EXTRA_METER = "meter"
        const val MODE_SHOW = "show"
        const val MODE_PATCH = "patch"
        const val MODE_HIDE = "hide"
        const val SURFACE_ID = "debug-ink"
        const val CONTENT_KEY = "debug-ink"
        val sequence = AtomicLong(System.currentTimeMillis())

        fun nextSeq(): Long = sequence.updateAndGet { previous ->
            maxOf(previous + 1L, System.currentTimeMillis())
        }
    }
}
