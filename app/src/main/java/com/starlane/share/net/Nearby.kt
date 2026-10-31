package com.starlane.share.net

import com.starlane.core.link.LinkKind

/** A real nearby device found by one of the discovery mechanisms (never synthesised). */
data class NearbyDevice(
    val deviceId: String,
    val username: String,
    val nickname: String,
    val model: String,
    val icon: Int,
    val lanHost: String? = null,
    val lanPort: Int = 0,
    val p2pAddress: String? = null,
    val p2pPort: Int = 0,
    val btAddress: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
) {
    val via: Set<LinkKind>
        get() = buildSet {
            if (lanHost != null) add(LinkKind.LAN)
            if (p2pAddress != null) add(LinkKind.WIFI_DIRECT)
            if (btAddress != null) add(LinkKind.BLUETOOTH)
        }

    val displayName: String get() = username.ifBlank { nickname.ifBlank { model } }

    /** Merges information about the same device coming from another discovery source. */
    fun merge(o: NearbyDevice) = copy(
        username = o.username.ifBlank { username }, nickname = o.nickname.ifBlank { nickname },
        model = o.model.ifBlank { model }, icon = if (o.icon != 0) o.icon else icon,
        lanHost = o.lanHost ?: lanHost, lanPort = if (o.lanHost != null) o.lanPort else lanPort,
        p2pAddress = o.p2pAddress ?: p2pAddress, p2pPort = if (o.p2pAddress != null) o.p2pPort else p2pPort,
        btAddress = o.btAddress ?: btAddress, lastSeen = maxOf(lastSeen, o.lastSeen),
    )
}

/** TXT-record encoding shared by NSD (LAN) and Wi-Fi Direct DNS-SD. Values are length-limited. */
object Txt {
    const val SERVICE_TYPE = "_starlane._tcp"
    const val DEFAULT_PORT = 47821

    fun encode(deviceId: String, username: String, nickname: String, model: String, icon: Int, port: Int): Map<String, String> =
        mapOf(
            "v" to "1", "id" to deviceId, "u" to username.take(24), "n" to nickname.take(24),
            "m" to model.take(40), "i" to icon.toString(), "p" to port.toString(),
        )

    fun decode(m: Map<String, String>): Parsed? {
        val id = m["id"] ?: return null
        if (!id.matches(Regex("[0-9a-f]{16}"))) return null
        return Parsed(
            id, (m["u"] ?: "").take(24), (m["n"] ?: "").take(24), (m["m"] ?: "").take(40),
            m["i"]?.toIntOrNull() ?: 0, m["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT,
        )
    }

    data class Parsed(val id: String, val u: String, val n: String, val m: String, val i: Int, val port: Int)
}

/** Discovery events delivered to the manager. */
interface DiscoverySink {
    fun found(d: NearbyDevice)
    fun lost(kind: LinkKind, deviceId: String)
}
