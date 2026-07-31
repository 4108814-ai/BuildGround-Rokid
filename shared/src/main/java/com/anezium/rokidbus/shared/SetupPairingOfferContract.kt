package com.anezium.rokidbus.shared

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale

data class SetupPairingOffer(
    val version: Int,
    val sessionId: String,
    val offerId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val host: String,
    val pairingPort: Int,
    val connectPort: Int,
    val pairingCode: String,
)

data class SetupPairingResult(
    val version: Int,
    val sessionId: String,
    val offerId: String,
    val ok: Boolean,
    val reason: String,
)

object SetupPairingFailureReason {
    const val EXPIRED = "EXPIRED"
    const val REPLAYED = "REPLAYED"
    const val WRONG_SESSION = "WRONG_SESSION"
    const val NOT_REQUESTED = "NOT_REQUESTED"
    const val PAIR_REFUSED = "PAIR_REFUSED"
    const val ARM_FAILED = "ARM_FAILED"
    const val UNSUPPORTED = "UNSUPPORTED"

    val ALL: Set<String> = setOf(
        EXPIRED,
        REPLAYED,
        WRONG_SESSION,
        NOT_REQUESTED,
        PAIR_REFUSED,
        ARM_FAILED,
        UNSUPPORTED,
    )
}

/**
 * Dedicated, short-lived setup pairing wire contract.
 *
 * The glasses timestamps describe only the intended lifespan. A receiver must start that lifespan
 * when the envelope arrives by calling [ttlMillis]; it must never compare the glasses timestamps
 * with its own wall clock.
 */
object SetupPairingOfferContract {
    const val VERSION = 1
    const val MAX_TTL_MS = 90_000L
    const val MAX_SESSION_ID_CHARS = 32
    const val OFFER_ID_CHARS = 16

    sealed interface OfferValidationResult {
        data class Valid(val offer: SetupPairingOffer) : OfferValidationResult
        data object Invalid : OfferValidationResult
    }

    sealed interface ResultValidationResult {
        data class Valid(val result: SetupPairingResult) : ResultValidationResult
        data object Invalid : ResultValidationResult
    }

    fun createOffer(
        sessionId: String,
        issuedAt: Long,
        expiresAt: Long,
        host: String,
        pairingPort: Int,
        connectPort: Int,
        pairingCode: String,
        offerId: String = newOfferId(),
    ): SetupPairingOffer? {
        val offer = SetupPairingOffer(
            version = VERSION,
            sessionId = sessionId,
            offerId = offerId,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            host = host,
            pairingPort = pairingPort,
            connectPort = connectPort,
            pairingCode = pairingCode,
        )
        return offer.takeIf(::isValidOffer)
    }

    fun offerToJson(offer: SetupPairingOffer): JSONObject = JSONObject()
        .put("version", offer.version)
        .put("sessionId", offer.sessionId)
        .put("offerId", offer.offerId)
        .put("issuedAt", offer.issuedAt)
        .put("expiresAt", offer.expiresAt)
        .put("host", offer.host)
        .put("pairingPort", offer.pairingPort)
        .put("connectPort", offer.connectPort)
        .put("pairingCode", offer.pairingCode)

    fun validateOffer(payload: JSONObject?): OfferValidationResult {
        val json = payload ?: return OfferValidationResult.Invalid
        val offer = SetupPairingOffer(
            version = requiredInt(json, "version") ?: return OfferValidationResult.Invalid,
            sessionId = requiredString(json, "sessionId") ?: return OfferValidationResult.Invalid,
            offerId = requiredString(json, "offerId") ?: return OfferValidationResult.Invalid,
            issuedAt = requiredLong(json, "issuedAt") ?: return OfferValidationResult.Invalid,
            expiresAt = requiredLong(json, "expiresAt") ?: return OfferValidationResult.Invalid,
            host = requiredString(json, "host") ?: return OfferValidationResult.Invalid,
            pairingPort = requiredInt(json, "pairingPort") ?: return OfferValidationResult.Invalid,
            connectPort = requiredInt(json, "connectPort") ?: return OfferValidationResult.Invalid,
            pairingCode = requiredString(json, "pairingCode") ?: return OfferValidationResult.Invalid,
        )
        return if (isValidOffer(offer)) {
            OfferValidationResult.Valid(offer)
        } else {
            OfferValidationResult.Invalid
        }
    }

    /**
     * Lifespan to apply from the receiver's arrival time. Null means the offer is already dead.
     */
    fun ttlMillis(offer: SetupPairingOffer): Long? {
        if (offer.expiresAt <= offer.issuedAt) return null
        val span = runCatching { Math.subtractExact(offer.expiresAt, offer.issuedAt) }
            .getOrNull()
            ?: return null
        return span.coerceAtMost(MAX_TTL_MS).takeIf { it > 0L }
    }

    fun createResult(
        sessionId: String,
        offerId: String,
        ok: Boolean,
        reason: String = "",
    ): SetupPairingResult? {
        val result = SetupPairingResult(
            version = VERSION,
            sessionId = sessionId,
            offerId = offerId,
            ok = ok,
            reason = reason,
        )
        return result.takeIf(::isValidResult)
    }

    fun resultToJson(result: SetupPairingResult): JSONObject = JSONObject()
        .put("version", result.version)
        .put("sessionId", result.sessionId)
        .put("offerId", result.offerId)
        .put("ok", result.ok)
        .also { payload ->
            if (!result.ok) payload.put("reason", result.reason)
        }

    fun validateResult(payload: JSONObject?): ResultValidationResult {
        val json = payload ?: return ResultValidationResult.Invalid
        val result = SetupPairingResult(
            version = requiredInt(json, "version") ?: return ResultValidationResult.Invalid,
            sessionId = requiredString(json, "sessionId") ?: return ResultValidationResult.Invalid,
            offerId = requiredString(json, "offerId") ?: return ResultValidationResult.Invalid,
            ok = requiredBoolean(json, "ok") ?: return ResultValidationResult.Invalid,
            reason = optionalString(json, "reason") ?: return ResultValidationResult.Invalid,
        )
        return if (isValidResult(result)) {
            ResultValidationResult.Valid(result)
        } else {
            ResultValidationResult.Invalid
        }
    }

    fun validSessionId(value: String): Boolean =
        value.length in 1..MAX_SESSION_ID_CHARS && LOWERCASE_HEX.matches(value)

    fun validOfferId(value: String): Boolean =
        value.length == OFFER_ID_CHARS && LOWERCASE_HEX.matches(value)

    fun validIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                (part == "0" || !part.startsWith('0')) &&
                part.toIntOrNull() in 0..255
        }
    }

    private fun isValidOffer(offer: SetupPairingOffer): Boolean =
        offer.version == VERSION &&
            validSessionId(offer.sessionId) &&
            validOfferId(offer.offerId) &&
            validIpv4Literal(offer.host) &&
            offer.pairingPort in 1..65535 &&
            offer.connectPort in 1..65535 &&
            PAIRING_CODE.matches(offer.pairingCode)

    private fun isValidResult(result: SetupPairingResult): Boolean =
        result.version == VERSION &&
            validSessionId(result.sessionId) &&
            validOfferId(result.offerId) &&
            if (result.ok) {
                result.reason.isEmpty()
            } else {
                result.reason in SetupPairingFailureReason.ALL
            }

    private fun requiredString(payload: JSONObject, key: String): String? =
        (payload.opt(key) as? String)?.takeIf(String::isNotEmpty)

    private fun optionalString(payload: JSONObject, key: String): String? {
        if (!payload.has(key)) return ""
        return payload.opt(key) as? String
    }

    private fun requiredBoolean(payload: JSONObject, key: String): Boolean? =
        payload.opt(key) as? Boolean

    private fun requiredInt(payload: JSONObject, key: String): Int? = when (val value = payload.opt(key)) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun requiredLong(payload: JSONObject, key: String): Long? = when (val value = payload.opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun newOfferId(): String = ByteArray(OFFER_ID_CHARS / 2)
        .also(secureRandom::nextBytes)
        .joinToString("") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }

    private val PAIRING_CODE = Regex("""\d{6}""")
    private val LOWERCASE_HEX = Regex("""[0-9a-f]+""")
    private val secureRandom = SecureRandom()
}
