package com.starlane.share.engine

import com.starlane.core.engine.Direction
import com.starlane.core.engine.TransferState
import com.starlane.core.engine.TurboNote
import com.starlane.core.engine.TurboStatus
import com.starlane.core.link.LinkKind
import com.starlane.share.net.NearbyDevice

/** Immutable snapshot of a transfer for the UI, refreshed at most twice per second. */
data class TransferUi(
    val id: String,
    val direction: Direction,
    val peerName: String,
    val peerModel: String,
    val peerId: String,
    val method: LinkKind,
    val state: TransferState,
    val reason: String,
    val paused: Boolean,
    val totalBytes: Long,
    val doneBytes: Long,
    val progress: Float,
    val speed: Double,
    val avgSpeed: Double,
    val etaSec: Long,
    val fileCount: Int,
    val doneFiles: Int,
    val failedFiles: Int,
    val skippedFiles: Int,
    val turbo: TurboStatus,
    val turboNote: TurboNote,
    val lanes: Int,
    val resumable: Boolean,
    val appCount: Int,
    val recovering: Boolean,
)

/** Live connection information. Signal values are real readings, or null when the platform gives none. */
data class ConnUi(
    val peerId: String,
    val peerName: String,
    val peerNick: String,
    val peerModel: String,
    val peerIcon: Int,
    val method: LinkKind,
    val cipher: String,
    val lanes: Int,
    val rttMs: Long,
    val rssiDbm: Int?,
    val linkMbps: Int?,
    val since: Long,
)

data class ConnectingUi(val device: NearbyDevice, val method: LinkKind?, val action: PeerAction)

enum class PeerAction { SEND, RECEIVE, NONE }

/** One-shot events for the UI (navigation, short messages). */
sealed class UiEvent {
    data class Connected(val action: PeerAction) : UiEvent()
    data class ConnectFailed(val reason: ConnectError) : UiEvent()
    data class Message(val key: Msg) : UiEvent()
    object OpenTransfer : UiEvent()
}

enum class ConnectError { NO_ROUTE, WIFI_DIRECT_FAILED, BLUETOOTH_FAILED, AUTH_FAILED, TIMEOUT, NEEDS_PERMISSION }

enum class Msg { DISCONNECTED, RECOVERING, RECOVERED, RECOVERY_FAILED, PEER_WANTS_TO_RECEIVE, NOT_CONNECTED, HISTORY_CLEARED }
