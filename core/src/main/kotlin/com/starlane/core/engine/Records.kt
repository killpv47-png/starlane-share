package com.starlane.core.engine

import com.starlane.core.util.WireReader
import com.starlane.core.util.WireWriter

/** Encoders/decoders for the small records exchanged during a transfer. */
internal object Rec {
    fun segBegin(tid: String, file: Int, seg: Int) = WireWriter(48).str(tid).i32(file).i32(seg)
    fun segEnd(hash: ByteArray) = WireWriter(40).raw(hash)
    fun segAck(tid: String, file: Int, seg: Int, ok: Boolean) = WireWriter(48).str(tid).i32(file).i32(seg).bool(ok)
    fun fileEnd(tid: String, file: Int, root: ByteArray) = WireWriter(64).str(tid).i32(file).raw(root)
    fun fileCommitted(tid: String, file: Int, ok: Boolean, reason: String) =
        WireWriter(64).str(tid).i32(file).bool(ok).str(reason)
    fun skipFile(tid: String, file: Int, reason: String) = WireWriter(64).str(tid).i32(file).str(reason)
    fun tid(tid: String) = WireWriter(32).str(tid)
    fun tidReason(tid: String, reason: String) = WireWriter(64).str(tid).str(reason)

    fun reader(b: ByteArray, off: Int, len: Int) = WireReader(b, off, off + len)
}

/** Payload of REQ_SEND: manifest plus the sender's chosen transfer parameters. */
internal data class SendRequest(
    val manifestBytes: ByteArray,
    val lanes: Int,
    val chunk: Int,
    val turbo: TurboStatus,
    val note: TurboNote,
    val resume: Boolean,
) {
    fun encode(): WireWriter = WireWriter(manifestBytes.size + 32)
        .bytes(manifestBytes).u8(lanes).i32(chunk).u8(turbo.ordinal).u8(note.ordinal).bool(resume)

    companion object {
        fun decode(r: WireReader): SendRequest = SendRequest(
            manifestBytes = r.bytes(Channel.MAX_CONTROL),
            lanes = r.u8().coerceIn(1, 8),
            chunk = r.i32().coerceIn(4 * 1024, 1024 * 1024),
            turbo = TurboStatus.entries.getOrElse(r.u8()) { TurboStatus.OFF },
            note = TurboNote.entries.getOrElse(r.u8()) { TurboNote.NONE },
            resume = r.bool(),
        )
    }
}
