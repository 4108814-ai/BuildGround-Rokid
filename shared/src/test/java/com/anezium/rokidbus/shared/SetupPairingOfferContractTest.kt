package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPairingOfferContractTest {
    @Test
    fun `valid offer round trips and receiver ttl is capped`() {
        val offer = requireNotNull(
            SetupPairingOfferContract.createOffer(
                sessionId = SESSION,
                offerId = OFFER,
                issuedAt = 9_000_000_000L,
                expiresAt = 9_000_500_000L,
                host = HOST,
                pairingPort = 37103,
                connectPort = 41827,
                pairingCode = CODE,
            ),
        )

        val validation = SetupPairingOfferContract.validateOffer(
            SetupPairingOfferContract.offerToJson(offer),
        )

        assertEquals(
            offer,
            (validation as SetupPairingOfferContract.OfferValidationResult.Valid).offer,
        )
        assertEquals(SetupPairingOfferContract.MAX_TTL_MS, SetupPairingOfferContract.ttlMillis(offer))
    }

    @Test
    fun `negative and zero spans are already dead regardless of wall clock`() {
        val backwards = validPayload().put("issuedAt", 5_000L).put("expiresAt", 4_999L)
        val zero = validPayload().put("issuedAt", -50_000L).put("expiresAt", -50_000L)

        val backwardsOffer = (
            SetupPairingOfferContract.validateOffer(backwards) as
                SetupPairingOfferContract.OfferValidationResult.Valid
            ).offer
        val zeroOffer = (
            SetupPairingOfferContract.validateOffer(zero) as
                SetupPairingOfferContract.OfferValidationResult.Valid
            ).offer

        assertEquals(null, SetupPairingOfferContract.ttlMillis(backwardsOffer))
        assertEquals(null, SetupPairingOfferContract.ttlMillis(zeroOffer))
    }

    @Test
    fun `malformed offer fields reject the whole payload`() {
        val malformed = listOf(
            validPayload().put("pairingCode", "12345"),
            validPayload().put("pairingPort", 0),
            validPayload().put("pairingPort", 70_000),
            validPayload().put("connectPort", 0),
            validPayload().put("host", "glasses.local"),
            validPayload().put("host", "256.1.2.3"),
            validPayload().put("host", "192.168.001.2"),
            // remove() hands back the value it took out, not the payload, so keep the JSONObject.
            validPayload().also { it.remove("pairingCode") },
            validPayload().also { it.remove("host") },
            validPayload().also { it.remove("issuedAt") },
            validPayload().also { it.remove("expiresAt") },
            validPayload().also { it.remove("sessionId") },
            validPayload().also { it.remove("offerId") },
        )

        malformed.forEach { payload ->
            assertTrue(
                payload.toString(),
                SetupPairingOfferContract.validateOffer(payload) ===
                    SetupPairingOfferContract.OfferValidationResult.Invalid,
            )
        }
    }

    @Test
    fun `identifiers must be lowercase hex and nonce is exactly sixteen characters`() {
        val malformed = listOf(
            validPayload().put("sessionId", "ABCDEF"),
            validPayload().put("sessionId", "not-hex"),
            validPayload().put("offerId", "ABCDEF0123456789"),
            validPayload().put("offerId", "abcdef012345678"),
            validPayload().put("offerId", "abcdef01234567890"),
        )

        malformed.forEach { payload ->
            assertTrue(
                SetupPairingOfferContract.validateOffer(payload) ===
                    SetupPairingOfferContract.OfferValidationResult.Invalid,
            )
        }
    }

    @Test
    fun `result accepts only the fixed non-sensitive failure reasons`() {
        val failure = requireNotNull(
            SetupPairingOfferContract.createResult(
                SESSION,
                OFFER,
                ok = false,
                reason = SetupPairingFailureReason.ARM_FAILED,
            ),
        )
        val valid = SetupPairingOfferContract.validateResult(
            SetupPairingOfferContract.resultToJson(failure),
        )
        val exceptionText = validResultPayload()
            .put("ok", false)
            .put("reason", "IOException at $HOST:37103 code=$CODE")

        assertEquals(
            failure,
            (valid as SetupPairingOfferContract.ResultValidationResult.Valid).result,
        )
        assertTrue(
            SetupPairingOfferContract.validateResult(exceptionText) ===
                SetupPairingOfferContract.ResultValidationResult.Invalid,
        )
    }

    @Test
    fun `new phone capability is additive for old glasses`() {
        val newPhone = PhoneHubCapabilitiesContract.toJson(
            PhoneHubCapabilitiesContract.create(
                features = BusCapabilityBits.PHONE_ASSISTED_SETUP,
                cameraConsumerName = null,
            ),
        )

        assertEquals(0, LegacyGlassesPhoneCapabilities.parseKnownFeatures(newPhone))
        assertEquals(1 shl 8, BusCapabilityBits.PHONE_ASSISTED_SETUP)
    }

    private fun validPayload(): JSONObject = JSONObject()
        .put("version", SetupPairingOfferContract.VERSION)
        .put("sessionId", SESSION)
        .put("offerId", OFFER)
        .put("issuedAt", 10_000L)
        .put("expiresAt", 20_000L)
        .put("host", HOST)
        .put("pairingPort", 37103)
        .put("connectPort", 41827)
        .put("pairingCode", CODE)

    private fun validResultPayload(): JSONObject = JSONObject()
        .put("version", SetupPairingOfferContract.VERSION)
        .put("sessionId", SESSION)
        .put("offerId", OFFER)
        .put("ok", true)

    private object LegacyGlassesPhoneCapabilities {
        private const val KNOWN_FEATURES =
            BusCapabilityBits.CAMERA_CONSUMER_READY or
                BusCapabilityBits.CAMERA_FROZEN_SPP or
                BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED

        fun parseKnownFeatures(payload: JSONObject): Int =
            payload.optInt("features", 0) and KNOWN_FEATURES
    }

    private companion object {
        const val SESSION = "0123456789abcdef"
        const val OFFER = "fedcba9876543210"
        const val HOST = "192.168.1.84"
        const val CODE = "123456"
    }
}
