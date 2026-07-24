package com.anezium.rokidbus.plugin.agents

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

data class Ed25519KeyPair(
    val seed: ByteArray,
    val publicKey: ByteArray,
)

/**
 * Minimal RFC 8032 Ed25519 implementation for the OpenClaw connect challenge.
 *
 * Android's platform Ed25519 provider is not available across the full minSdk 30
 * range, and the plugin dependency contract does not permit adding a crypto
 * provider. This deliberately implements only key derivation and signing.
 */
object Ed25519 {
    private val TWO = BigInteger.valueOf(2)
    private val P = TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L = TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D = m(BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)))
    private val SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
    private val BASE_Y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val BASE_X = recoverX(BASE_Y, false)
    private val BASE = Point(BASE_X, BASE_Y, BigInteger.ONE, m(BASE_X.multiply(BASE_Y)))
    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    fun generate(random: SecureRandom = SecureRandom()): Ed25519KeyPair {
        val seed = ByteArray(32).also(random::nextBytes)
        return fromSeed(seed)
    }

    fun fromSeed(seed: ByteArray): Ed25519KeyPair {
        require(seed.size == 32)
        val expanded = sha512(seed)
        val scalarBytes = expanded.copyOfRange(0, 32).apply {
            this[0] = (this[0].toInt() and 248).toByte()
            this[31] = ((this[31].toInt() and 63) or 64).toByte()
        }
        val publicKey = encodePoint(scalarMultiply(BASE, littleEndianInteger(scalarBytes)))
        return Ed25519KeyPair(seed.copyOf(), publicKey)
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val pair = fromSeed(seed)
        val expanded = sha512(seed)
        val scalarBytes = expanded.copyOfRange(0, 32).apply {
            this[0] = (this[0].toInt() and 248).toByte()
            this[31] = ((this[31].toInt() and 63) or 64).toByte()
        }
        val scalar = littleEndianInteger(scalarBytes)
        val prefix = expanded.copyOfRange(32, 64)
        val r = littleEndianInteger(sha512(prefix + message)).mod(L)
        val encodedR = encodePoint(scalarMultiply(BASE, r))
        val challenge = littleEndianInteger(sha512(encodedR + pair.publicKey + message)).mod(L)
        val s = r.add(challenge.multiply(scalar)).mod(L)
        return encodedR + littleEndianBytes(s, 32)
    }

    private data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    private fun add(left: Point, right: Point): Point {
        val a = m(left.y.subtract(left.x).multiply(right.y.subtract(right.x)))
        val b = m(left.y.add(left.x).multiply(right.y.add(right.x)))
        val c = m(TWO.multiply(D).multiply(left.t).multiply(right.t))
        val d = m(TWO.multiply(left.z).multiply(right.z))
        val e = m(b.subtract(a))
        val f = m(d.subtract(c))
        val g = m(d.add(c))
        val h = m(b.add(a))
        return Point(
            x = m(e.multiply(f)),
            y = m(g.multiply(h)),
            z = m(f.multiply(g)),
            t = m(e.multiply(h)),
        )
    }

    private fun double(point: Point): Point {
        val a = m(point.x.multiply(point.x))
        val b = m(point.y.multiply(point.y))
        val c = m(TWO.multiply(point.z).multiply(point.z))
        val d = m(a.negate())
        val e = m(point.x.add(point.y).pow(2).subtract(a).subtract(b))
        val g = m(d.add(b))
        val f = m(g.subtract(c))
        val h = m(d.subtract(b))
        return Point(
            x = m(e.multiply(f)),
            y = m(g.multiply(h)),
            z = m(f.multiply(g)),
            t = m(e.multiply(h)),
        )
    }

    private fun scalarMultiply(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var value = scalar
        while (value.signum() > 0) {
            if (value.testBit(0)) result = add(result, addend)
            addend = double(addend)
            value = value.shiftRight(1)
        }
        return result
    }

    private fun encodePoint(point: Point): ByteArray {
        val zInverse = point.z.modInverse(P)
        val x = m(point.x.multiply(zInverse))
        val y = m(point.y.multiply(zInverse))
        return littleEndianBytes(y, 32).apply {
            if (x.testBit(0)) this[31] = (this[31].toInt() or 0x80).toByte()
        }
    }

    private fun recoverX(y: BigInteger, odd: Boolean): BigInteger {
        val ySquared = m(y.multiply(y))
        val xx = m(ySquared.subtract(BigInteger.ONE).multiply(
            D.multiply(ySquared).add(BigInteger.ONE).modInverse(P),
        ))
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (m(x.multiply(x)) != xx) x = m(x.multiply(SQRT_M1))
        check(m(x.multiply(x)) == xx)
        if (x.testBit(0) != odd) x = P.subtract(x)
        return x
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    private fun littleEndianInteger(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray())

    private fun littleEndianBytes(value: BigInteger, size: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(size)
        for (index in 0 until minOf(size, bigEndian.size)) {
            out[index] = bigEndian[bigEndian.lastIndex - index]
        }
        return out
    }

    private fun m(value: BigInteger): BigInteger = value.mod(P)
}
