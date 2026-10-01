package com.meshline.core

enum class CryptoCheckResult {
    VALID,
    INVALID,
    UNSIGNED_SOS
}

interface CryptoEngine {
    fun getMyNodeId(): String
    fun sign(packet: Packet): Packet
    fun check(packet: Packet): CryptoCheckResult
}

interface MessageStore {
    fun save(packet: Packet)
    fun get(msgId: String): Packet?
    fun markAcknowledged(refMsgId: String)
    fun isAcknowledged(msgId: String): Boolean
    fun getPendingForRelay(): List<Packet>
}

interface MetricsCollector {
    fun rejected(reason: String)
    fun packetRelayed(msgId: String, hops: Int)
    fun sosDelivered(msgId: String)
}

interface PowerPolicy {
    fun allowsRelay(priority: Int): Boolean
}
