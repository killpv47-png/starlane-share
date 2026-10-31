package com.starlane.core.util

import java.nio.charset.StandardCharsets

/** Thrown whenever a peer sends malformed, oversized or semantically invalid data. */
class ProtocolException(message: String) : Exception(message)

/**
 * Minimal growable binary writer (big endian). Used for control messages; avoids pulling in a
 * serialization library and keeps allocations predictable.
 */
class WireWriter(initial: Int = 256) {
    var buf = ByteArray(initial)
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
    }

    fun u8(v: Int) = apply { ensure(1); buf[size++] = v.toByte() }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun u16(v: Int) = apply { ensure(2); buf[size++] = (v ushr 8).toByte(); buf[size++] = v.toByte() }
    fun i32(v: Int) = apply {
        ensure(4)
        buf[size++] = (v ushr 24).toByte(); buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte(); buf[size++] = v.toByte()
    }
    fun i64(v: Long) = apply { i32((v ushr 32).toInt()); i32(v.toInt()) }
    fun raw(b: ByteArray, off: Int = 0, len: Int = b.size) = apply {
        ensure(len); System.arraycopy(b, off, buf, size, len); size += len
    }
    fun bytes(b: ByteArray) = apply { i32(b.size); raw(b) }
    fun str(s: String) = apply {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        require(b.size <= 0xFFFF) { "string too long" }
        u16(b.size); raw(b)
    }
    fun strMap(m: Map<String, String>) = apply {
        u16(m.size); m.forEach { (k, v) -> str(k); str(v) }
    }
    fun strList(l: List<String>) = apply { u16(l.size); l.forEach { str(it) } }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Bounds-checked reader. Every read validates remaining length, so hostile input cannot overflow. */
class WireReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
    constructor(b: ByteArray) : this(b, 0, b.size)

    val remaining get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw ProtocolException("truncated message")
    }

    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }
    fun bool(): Boolean = u8() != 0
    fun u16(): Int {
        need(2)
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2; return v
    }
    fun i32(): Int {
        need(4)
        val v = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
        pos += 4; return v
    }
    fun i64(): Long { val hi = i32().toLong(); val lo = i32().toLong() and 0xFFFFFFFFL; return (hi shl 32) or lo }
    fun raw(n: Int): ByteArray { need(n); val r = buf.copyOfRange(pos, pos + n); pos += n; return r }
    fun bytes(max: Int): ByteArray {
        val n = i32()
        if (n < 0 || n > max) throw ProtocolException("byte field too large: $n")
        return raw(n)
    }
    fun str(max: Int = 4096): String {
        val n = u16()
        if (n > max) throw ProtocolException("string too long: $n")
        need(n)
        val s = String(buf, pos, n, StandardCharsets.UTF_8); pos += n; return s
    }
    fun strMap(maxEntries: Int = 64, maxValue: Int = 16384): Map<String, String> {
        val n = u16()
        if (n > maxEntries) throw ProtocolException("map too large")
        val m = LinkedHashMap<String, String>(n)
        repeat(n) { m[str(256)] = str(maxValue) }
        return m
    }
    fun strList(maxEntries: Int = 256, maxLen: Int = 1024): List<String> {
        val n = u16()
        if (n > maxEntries) throw ProtocolException("list too large")
        return List(n) { str(maxLen) }
    }
}

object Hex {
    private val digits = "0123456789abcdef".toCharArray()
    fun encode(b: ByteArray, len: Int = b.size): String {
        val out = CharArray(len * 2)
        for (i in 0 until len) {
            val v = b[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]; out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }
    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
