package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.SetupPairingOfferContract

/**
 * Validation for the last-resort form, where the owner types what the lens shows.
 *
 * The old form took three loose fields and let the transport discover the mistake, so a typo came
 * back as a pairing failure minutes later. These say which field is wrong and why, before anything
 * is sent.
 */
internal object ManualEndpointInput {
    enum class EndpointError { EMPTY, FORMAT, IP, PORT }

    enum class CodeError { EMPTY, FORMAT }

    sealed interface Endpoint {
        data class Valid(val host: String, val port: Int) : Endpoint
        data class Invalid(val error: EndpointError) : Endpoint
    }

    sealed interface Code {
        data class Valid(val code: String) : Code
        data class Invalid(val error: CodeError) : Code
    }

    /** One field, `IP:port`, because that is exactly how the glasses print it. */
    fun parseEndpoint(raw: String): Endpoint {
        val value = raw.trim()
        if (value.isEmpty()) return Endpoint.Invalid(EndpointError.EMPTY)
        // Split from the right: an IPv4 literal never contains a colon, so anything else is the
        // owner having pasted something that is not an endpoint.
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator == value.length - 1) {
            return Endpoint.Invalid(EndpointError.FORMAT)
        }
        val host = value.substring(0, separator).trim()
        val portText = value.substring(separator + 1).trim()
        if (host.isEmpty() || portText.isEmpty()) return Endpoint.Invalid(EndpointError.FORMAT)
        if (!SetupPairingOfferContract.validIpv4Literal(host)) {
            return Endpoint.Invalid(EndpointError.IP)
        }
        if (!portText.all { it.isAsciiDigit() }) return Endpoint.Invalid(EndpointError.PORT)
        val port = portText.toIntOrNull() ?: return Endpoint.Invalid(EndpointError.PORT)
        if (port !in 1..65535) return Endpoint.Invalid(EndpointError.PORT)
        return Endpoint.Valid(host, port)
    }

    fun parseCode(raw: String): Code {
        val value = raw.trim()
        if (value.isEmpty()) return Code.Invalid(CodeError.EMPTY)
        if (value.length != PAIRING_CODE_DIGITS || !value.all { it.isAsciiDigit() }) {
            return Code.Invalid(CodeError.FORMAT)
        }
        return Code.Valid(value)
    }

    private const val PAIRING_CODE_DIGITS = 6

    // Char.isDigit() takes any Unicode decimal digit, but the pairing engine (and adbd itself)
    // only speak ASCII; accepting more here turns a fixable format error into a baffling refusal
    // one screen later.
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
