package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualEndpointInputTest {
    @Test
    fun `a well formed endpoint parses`() {
        val parsed = ManualEndpointInput.parseEndpoint(" 192.168.1.20:37135 ")
        assertEquals(ManualEndpointInput.Endpoint.Valid("192.168.1.20", 37135), parsed)
    }

    @Test
    fun `an empty endpoint is its own error, not a format complaint`() {
        assertEquals(
            ManualEndpointInput.Endpoint.Invalid(ManualEndpointInput.EndpointError.EMPTY),
            ManualEndpointInput.parseEndpoint("   "),
        )
    }

    @Test
    fun `a missing port is a format error`() {
        assertEquals(
            ManualEndpointInput.Endpoint.Invalid(ManualEndpointInput.EndpointError.FORMAT),
            ManualEndpointInput.parseEndpoint("192.168.1.20"),
        )
        assertEquals(
            ManualEndpointInput.Endpoint.Invalid(ManualEndpointInput.EndpointError.FORMAT),
            ManualEndpointInput.parseEndpoint("192.168.1.20:"),
        )
    }

    @Test
    fun `octets are actually checked`() {
        assertEquals(
            ManualEndpointInput.Endpoint.Invalid(ManualEndpointInput.EndpointError.IP),
            ManualEndpointInput.parseEndpoint("192.168.1.999:37135"),
        )
        assertEquals(
            ManualEndpointInput.Endpoint.Invalid(ManualEndpointInput.EndpointError.IP),
            ManualEndpointInput.parseEndpoint("192.168.1:37135"),
        )
    }

    @Test
    fun `port ranges are actually checked`() {
        assertEquals(
            ManualEndpointInput.Endpoint.Invalid(ManualEndpointInput.EndpointError.PORT),
            ManualEndpointInput.parseEndpoint("192.168.1.20:0"),
        )
        assertEquals(
            ManualEndpointInput.Endpoint.Invalid(ManualEndpointInput.EndpointError.PORT),
            ManualEndpointInput.parseEndpoint("192.168.1.20:70000"),
        )
        assertEquals(
            ManualEndpointInput.Endpoint.Invalid(ManualEndpointInput.EndpointError.PORT),
            ManualEndpointInput.parseEndpoint("192.168.1.20:37a35"),
        )
    }

    @Test
    fun `the code is six digits and nothing else`() {
        assertEquals(
            ManualEndpointInput.Code.Valid("123456"),
            ManualEndpointInput.parseCode(" 123456 "),
        )
        assertEquals(
            ManualEndpointInput.Code.Invalid(ManualEndpointInput.CodeError.EMPTY),
            ManualEndpointInput.parseCode(""),
        )
        assertEquals(
            ManualEndpointInput.Code.Invalid(ManualEndpointInput.CodeError.FORMAT),
            ManualEndpointInput.parseCode("12345"),
        )
        assertEquals(
            ManualEndpointInput.Code.Invalid(ManualEndpointInput.CodeError.FORMAT),
            ManualEndpointInput.parseCode("12345a"),
        )
    }
}
