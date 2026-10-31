package com.starlane.core.engine

import com.starlane.core.crypto.CipherSuite
import com.starlane.core.link.LinkKind
import com.starlane.core.link.SocketTuning
import com.starlane.core.proto.Capabilities

enum class TurboStatus { OFF, ACTIVE, LIMITED, UNAVAILABLE }

/** Why Turbo Mode is not fully active; mapped to localized strings in the UI. */
enum class TurboNote { NONE, LOCAL_LOW_END, PEER_NO_TURBO, PEER_LOW_END, LINK_BLUETOOTH, LINK_USB }

data class TransferTuning(
    val lanes: Int,
    val chunkSize: Int,
    val segmentSize: Int,
    val socket: SocketTuning,
    val turbo: TurboStatus,
    val note: TurboNote,
)

/** Hardware profile of the local device, supplied by the platform layer. */
data class DeviceProfile(
    val cores: Int,
    val lowRam: Boolean,
    val totalRamMb: Long,
    val sdkInt: Int = 0,
) {
    /** Low-end: Android "low RAM" flag, < 1.5 GB RAM, or a single core. */
    val lowEnd: Boolean get() = lowRam || totalRamMb in 1..1536 || cores < 2
}

/**
 * Chooses transfer parameters from the capabilities of BOTH devices and the active link.
 * Nothing here is a fixed "best" value: the defaults are conservative, and Turbo Mode scales lanes,
 * chunk sizes and socket buffers to what the weaker of the two devices can sustain. The numbers
 * were selected with the loopback benchmark in the core test-suite (see BenchmarkTest).
 */
object Tuner {
    const val NORMAL_CHUNK = 64 * 1024
    const val TURBO_CHUNK = 256 * 1024
    const val LOW_END_CHUNK = 32 * 1024

    fun localCaps(device: DeviceProfile, turboEnabled: Boolean, listenPort: Int, bench: Map<CipherSuite, Int>): Capabilities {
        val maxLanes = when {
            device.lowEnd -> 1
            device.cores >= 8 -> 4
            device.cores >= 4 -> 3
            else -> 2
        }
        return Capabilities(
            cipherBench = bench.mapKeys { it.key.id },
            cores = device.cores,
            maxLanes = maxLanes,
            turbo = turboEnabled && !device.lowEnd,
            lowRam = device.lowEnd,
            listenPort = listenPort,
            maxChunk = if (device.lowEnd) 128 * 1024 else 512 * 1024,
        )
    }

    fun choose(kind: LinkKind, local: Capabilities, peer: Capabilities, turboRequested: Boolean): TransferTuning {
        val peerChunk = minOf(local.maxChunk, peer.maxChunk)
        when (kind) {
            LinkKind.BLUETOOTH -> return TransferTuning(
                1, minOf(16 * 1024, peerChunk), 512 * 1024, SocketTuning.DEFAULT,
                if (turboRequested) TurboStatus.LIMITED else TurboStatus.OFF,
                if (turboRequested) TurboNote.LINK_BLUETOOTH else TurboNote.NONE,
            )
            LinkKind.USB -> return TransferTuning(
                1, minOf(64 * 1024, peerChunk), 4 * 1024 * 1024, SocketTuning.DEFAULT,
                if (turboRequested) TurboStatus.LIMITED else TurboStatus.OFF,
                if (turboRequested) TurboNote.LINK_USB else TurboNote.NONE,
            )
            else -> Unit
        }
        val anyLowEnd = local.lowRam || peer.lowRam
        val normal = TransferTuning(
            lanes = if (anyLowEnd) 1 else minOf(2, local.maxLanes, peer.maxLanes),
            chunkSize = minOf(if (anyLowEnd) LOW_END_CHUNK else NORMAL_CHUNK, peerChunk),
            segmentSize = 4 * 1024 * 1024,
            socket = if (anyLowEnd) SocketTuning.LOW_RAM else SocketTuning.DEFAULT,
            turbo = TurboStatus.OFF,
            note = TurboNote.NONE,
        )
        if (!turboRequested) return normal
        if (local.lowRam) return normal.copy(turbo = TurboStatus.UNAVAILABLE, note = TurboNote.LOCAL_LOW_END)
        if (!peer.turbo) {
            val note = if (peer.lowRam) TurboNote.PEER_LOW_END else TurboNote.PEER_NO_TURBO
            // Still use what the local side can do safely: bigger chunks for fewer records.
            return normal.copy(
                chunkSize = minOf(128 * 1024, peerChunk), turbo = TurboStatus.LIMITED, note = note,
            )
        }
        return TransferTuning(
            lanes = minOf(local.maxLanes, peer.maxLanes).coerceAtLeast(1),
            chunkSize = minOf(TURBO_CHUNK, peerChunk),
            segmentSize = 8 * 1024 * 1024,
            socket = SocketTuning.TURBO,
            turbo = TurboStatus.ACTIVE,
            note = TurboNote.NONE,
        )
    }
}
