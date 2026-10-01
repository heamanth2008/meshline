package com.meshline.core

enum class PacketType(val id: Int) {
    SOS(0),
    ACK(1),
    TEXT(2),
    ALERT(3),
    IDENTITY(4),
    CHECKIN(5),
    HAZARD(6),
    VOICE(7),
    MISSING(8);

    companion object {
        fun fromId(id: Int) = entries.find { it.id == id } ?: TEXT
    }
}

object PacketFlags {
    const val NONE = 0
    const val UNVERIFIABLE = 1 shl 0
}

data class Packet(
    val msgId: String,
    val type: PacketType,
    val priority: Int, // 0 (highest) to 5
    val senderKeyHash: String,
    val senderCounter: Long,
    val dest: String = "ALL",
    val createdAt: Long,
    val expiresAt: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val payload: String, // JSON payload: Triage, text, or refId + status
    val signature: String? = null,
    val flags: Int = PacketFlags.NONE,
    val ttl: Int = 30,
    val hopCount: Int = 0
) {
    fun isUnverifiable(): Boolean = (flags and PacketFlags.UNVERIFIABLE) != 0

    fun immutableBytesForSigning(): ByteArray {
        val raw = "$msgId|${type.id}|$priority|$senderKeyHash|$senderCounter|$dest|$createdAt|$expiresAt|$lat|$lon|$accuracy|$payload"
        return raw.toByteArray(Charsets.UTF_8)
    }

    fun isForMe(myNodeId: String): Boolean = dest == "ALL" || dest == myNodeId
}

data class TriagePayload(
    val category: String, // Medical, Trapped, Fire, Flood, Danger, Supplies
    val severity: String, // Low, High, Critical
    val peopleCount: Int,
    val batteryPct: Int
)

data class AckPayload(
    val refMsgId: String,
    val status: String // Received, Dispatched, Resolved
)
