package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfacePatchResult
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal data class CanonicalPhoneNotice(
    val ownerPluginId: String,
    val content: NoticeSurfaceContent,
    val payload: JSONObject,
    /** Restarted by every accepted update. */
    val ttlDeadlineMs: Long,
    /** Fixed at the first show. No update can push a notice past it. */
    val hardDeadlineMs: Long,
    /** The wearer has already picked. A notice takes exactly one answer. */
    val answered: Boolean = false,
) {
    val deadlineMs: Long get() = minOf(ttlDeadlineMs, hardDeadlineMs)
}

/** What the phone owes a `/notice/action` arriving from the glasses. */
internal sealed interface PhoneNoticeActionResult {
    data class Owner(val ownerPluginId: String) : PhoneNoticeActionResult

    /** The one answer was already taken. Distinct from [NotCurrent] on purpose. */
    data object AlreadyAnswered : PhoneNoticeActionResult

    /** No such notice, or no such action on the one that is up. */
    data object NotCurrent : PhoneNoticeActionResult
}

internal sealed interface PhoneNoticeShowResult {
    data class Accepted(
        val notice: CanonicalPhoneNotice,
        val replacedOwnerPluginId: String?,
    ) : PhoneNoticeShowResult

    data class Rejected(val code: String) : PhoneNoticeShowResult
}

internal sealed interface PhoneNoticeUpdateResult {
    data class Accepted(val notice: CanonicalPhoneNotice) : PhoneNoticeUpdateResult
    data class Rejected(val code: String) : PhoneNoticeUpdateResult

    /** Nothing visible, or not this plugin's slot. Logged, never an error. */
    data object Ignored : PhoneNoticeUpdateResult
}

internal sealed interface PhoneNoticeClearResult {
    data class Cleared(
        val ownerPluginId: String,
        val reason: NoticeCloseReason,
        val payload: JSONObject,
    ) : PhoneNoticeClearResult

    data object Ignored : PhoneNoticeClearResult
}

/**
 * Canonical single-slot notice state on the phone.
 *
 * Deliberately unlike [PhonePinState] in one respect: a notice is never held
 * for a link that is down. A pin is a standing fact and is worth delivering
 * late; a notice is a moment, and one delivered thirty seconds after the event
 * is a lie about the present. When the glasses cannot be reached the plugin is
 * told so and can decide for itself.
 */
internal class PhoneNoticeState(
    private val nowMs: () -> Long,
    initialSequence: Long = System.currentTimeMillis(),
) {
    private val sequence = AtomicLong(initialSequence)
    private val recentMessagesByPlugin = mutableMapOf<String, ArrayDeque<Long>>()
    private var active: CanonicalPhoneNotice? = null

    @Synchronized
    fun show(ownerPluginId: String, payload: JSONObject): PhoneNoticeShowResult {
        val expectedSurfaceId = "$ownerPluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        if (payload.optString("surfaceId") != expectedSurfaceId ||
            payload.optString("localSurfaceId") != NoticeSurfaceContract.LOCAL_SURFACE_ID ||
            payload.optString("ownerPluginId") != ownerPluginId
        ) {
            return PhoneNoticeShowResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        val validation = NoticeSurfaceContract.validateShow(payload)
        if (validation !is NoticeSurfaceValidationResult.Valid) {
            return PhoneNoticeShowResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        val now = nowMs()
        if (!admit(ownerPluginId, now)) {
            return PhoneNoticeShowResult.Rejected(NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED)
        }

        val previousOwner = active?.ownerPluginId
        val content = validation.content
        val notice = CanonicalPhoneNotice(
            ownerPluginId = ownerPluginId,
            content = content,
            payload = normalized(expectedSurfaceId, ownerPluginId, content),
            ttlDeadlineMs = now + content.ttlMs,
            hardDeadlineMs = now + NoticeSurfaceContract.MAX_LIFETIME_MS,
        )
        active = notice
        return PhoneNoticeShowResult.Accepted(
            notice,
            previousOwner?.takeIf { it != ownerPluginId },
        )
    }

    @Synchronized
    fun update(ownerPluginId: String, payload: JSONObject): PhoneNoticeUpdateResult {
        val current = active ?: return PhoneNoticeUpdateResult.Ignored
        if (current.ownerPluginId != ownerPluginId) return PhoneNoticeUpdateResult.Ignored

        val patch = NoticeSurfaceContract.validateUpdate(payload)
        if (patch !is NoticeSurfacePatchResult.Valid) {
            return PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        val patched = patch.patch.applyTo(current.content)
        if (patched.title.isNullOrEmpty() && patched.body.isNullOrEmpty()) {
            return PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        val now = nowMs()
        if (!admit(ownerPluginId, now)) {
            return PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED)
        }

        val surfaceId = "$ownerPluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        // An update that carries the actions field is a new question and is owed
        // a new answer; one that leaves it out is the owner driving an answered
        // band as a display and must not reopen it.
        val answered = if (patch.patch.actions != null) false else current.answered
        val notice = current.copy(
            content = patched,
            payload = normalized(surfaceId, ownerPluginId, patched, answered),
            ttlDeadlineMs = now + patched.ttlMs,
            answered = answered,
        )
        active = notice
        return PhoneNoticeUpdateResult.Accepted(notice)
    }

    @Synchronized
    fun hide(ownerPluginId: String): PhoneNoticeClearResult =
        if (active?.ownerPluginId == ownerPluginId) {
            clearActive(NoticeCloseReason.OWNER)
        } else {
            PhoneNoticeClearResult.Ignored
        }

    /** The glasses reported the wearer dismissed it, or the band timed out there. */
    @Synchronized
    fun closedByGlasses(surfaceId: String, reason: NoticeCloseReason): PhoneNoticeClearResult {
        val current = active ?: return PhoneNoticeClearResult.Ignored
        val expected = "${current.ownerPluginId}:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        if (surfaceId != expected) return PhoneNoticeClearResult.Ignored
        return clearActive(reason)
    }

    @Synchronized
    fun ownerLostAccess(ownerPluginId: String): PhoneNoticeClearResult =
        if (active?.ownerPluginId == ownerPluginId) {
            clearActive(NoticeCloseReason.DISCONNECT)
        } else {
            PhoneNoticeClearResult.Ignored
        }

    @Synchronized
    fun ownerPluginId(): String? = active?.ownerPluginId

    /**
     * Takes the canonical notice's one answer and names the plugin owed it.
     *
     * Checked against the canonical content rather than trusted from the wire,
     * so a pick that raced a replacement is dropped instead of being handed to
     * whoever holds the slot now. The answered flag lives here as well as on the
     * glasses because the duplicate that prompted this rule is a race, and a
     * race is exactly what survives one side losing its state.
     */
    @Synchronized
    fun takeAnswer(surfaceId: String, actionId: String): PhoneNoticeActionResult {
        if (actionId.isBlank()) return PhoneNoticeActionResult.NotCurrent
        val current = active ?: return PhoneNoticeActionResult.NotCurrent
        val expected = "${current.ownerPluginId}:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        if (surfaceId != expected) return PhoneNoticeActionResult.NotCurrent
        if (current.content.actions.none { it.id == actionId }) {
            return PhoneNoticeActionResult.NotCurrent
        }
        if (current.answered) return PhoneNoticeActionResult.AlreadyAnswered
        active = current.copy(answered = true)
        return PhoneNoticeActionResult.Owner(current.ownerPluginId)
    }

    @Synchronized
    fun expireIfDue(): PhoneNoticeClearResult {
        val deadline = active?.deadlineMs ?: return PhoneNoticeClearResult.Ignored
        return if (nowMs() >= deadline) clearActive(NoticeCloseReason.TIMEOUT) else PhoneNoticeClearResult.Ignored
    }

    @Synchronized
    fun expiryDeadlineMs(): Long? = active?.deadlineMs

    /**
     * The canonical payload the glasses receive.
     *
     * An answered notice is sent without its actions. The glasses apply an
     * update as a patch, and a patch that carries the actions field is a new
     * question there -- so forwarding the row the wearer already answered, on
     * an ordinary text update, would resurrect it under them.
     */
    private fun normalized(
        surfaceId: String,
        ownerPluginId: String,
        content: NoticeSurfaceContent,
        answered: Boolean = false,
    ): JSONObject = NoticeSurfaceContract
        .toPayload(
            surfaceId,
            if (answered) content.copy(actions = emptyList()) else content,
        )
        .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", ownerPluginId)
        .put("seq", sequence.incrementAndGet())

    /**
     * Sliding one-second window shared by show and update, so a plugin cannot
     * dodge the budget by alternating between them.
     */
    private fun admit(ownerPluginId: String, now: Long): Boolean {
        val window = recentMessagesByPlugin.getOrPut(ownerPluginId) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= RATE_WINDOW_MS) {
            window.removeFirst()
        }
        if (window.size >= NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND) return false
        window.addLast(now)
        return true
    }

    private fun clearActive(reason: NoticeCloseReason): PhoneNoticeClearResult.Cleared {
        val notice = checkNotNull(active)
        active = null
        val surfaceId = "${notice.ownerPluginId}:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        return PhoneNoticeClearResult.Cleared(
            ownerPluginId = notice.ownerPluginId,
            reason = reason,
            payload = JSONObject()
                .put("surfaceId", surfaceId)
                .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
                .put("ownerPluginId", notice.ownerPluginId)
                .put("seq", sequence.incrementAndGet()),
        )
    }

    private companion object {
        const val RATE_WINDOW_MS = 1_000L
    }
}
