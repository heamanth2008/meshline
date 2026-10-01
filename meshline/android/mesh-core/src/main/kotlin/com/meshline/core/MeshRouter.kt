package com.meshline.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * MESHLiNE Core Router.
 * Implements single-threaded execution model, epidemic store-carry-forward,
 * breadcrumb ACK routing, 24-hr clock drift grace, and Sybil rate limiting.
 * Reference: Plan Revision 3, Sections 5, 8, and Appendix B.
 */
class MeshRouter(
    val myNodeId: String,
    private val transport: Transport,
    private val crypto: CryptoEngine,
    private val store: MessageStore,
    private val limiter: RateLimiter,
    private val metrics: MetricsCollector,
    private val power: PowerPolicy,
    private val onDeliverToUi: (Packet) -> Unit
) : TransportListener {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val breadcrumbs = ConcurrentHashMap<String, String>() // msg_id -> nodeId it arrived from
    private val replayWindows = ConcurrentHashMap<String, ReplayWindow>()

    init {
        transport.listener = this
    }

    override fun onPeerConnected(nodeId: String) {
        executor.execute {
            // Anti-entropy sync: exchange unacknowledged packets
            val pending = store.getPendingForRelay()
            for (pkt in pending) {
                if (pkt.ttl > 0 && !store.isAcknowledged(pkt.msgId)) {
                    transport.send(nodeId, pkt.toWireBytes())
                }
            }
        }
    }

    override fun onPeerLost(nodeId: String) {
        // Handled automatically via breadcrumb fallback in routeAck
    }

    override fun onBytes(nodeId: String, bytes: ByteArray) {
        executor.execute {
            onReceive(nodeId, bytes)
        }
    }

    fun onReceive(from: String, bytes: ByteArray) {
        val pkt = PacketSerializer.fromWireBytes(bytes) ?: return

        // 1. Deduplication
        if (seen.contains(pkt.msgId)) return

        // 2. Cryptographic and Replay checks
        when (crypto.check(pkt)) {
            CryptoCheckResult.INVALID -> {
                metrics.rejected("bad_signature")
                return
            }
            CryptoCheckResult.UNSIGNED_SOS -> {
                // Emergency beats authenticity fallback (Section 9.2)
                if (!limiter.allowUnsigned(from)) {
                    metrics.rejected("unsigned_sos_rate_exceeded")
                    return
                }
            }
            CryptoCheckResult.VALID -> {
                val replay = replayWindows.computeIfAbsent(pkt.senderKeyHash) { ReplayWindow() }
                if (!replay.accept(pkt.senderCounter)) {
                    metrics.rejected("replay")
                    return
                }
                if (!limiter.allowSender(pkt.senderKeyHash)) {
                    metrics.rejected("sender_rate_limit")
                    return
                }
            }
        }

        // 3. Physical link rate limit (Sybil defense)
        if (!limiter.allowLink(from)) {
            metrics.rejected("link_rate_limit")
            return
        }

        // 4. Clock-drift rule: 24-hr grace period
        if (isExpired(pkt)) {
            metrics.rejected("expired")
            return
        }

        // 5. Store & Mark Seen
        store.save(pkt)
        seen.add(pkt.msgId)

        // 6. Record Breadcrumb for targeted reverse path of ACKs
        breadcrumbs[pkt.msgId] = from

        // 7. Deliver to local UI if destined for me or broadcast
        if (pkt.isForMe(myNodeId)) {
            onDeliverToUi(pkt)
        }

        // 8. If packet is ACK, mark referenced SOS as resolved/acknowledged
        if (pkt.type == PacketType.ACK) {
            val refId = PacketSerializer.extractRefMsgId(pkt.payload)
            if (refId != null) {
                store.markAcknowledged(refId)
            }
        }

        // If arrived at final destination, stop forwarding
        if (pkt.dest == myNodeId) return

        // 9. Decrement TTL and check power policy
        val next = pkt.copy(ttl = pkt.ttl - 1, hopCount = pkt.hopCount + 1)
        if (next.ttl <= 0 || !power.allowsRelay(pkt.priority)) return

        // 10. Forwarding path: Targeted breadcrumb routing for ACKs, Epidemic flood for SOS/others
        if (pkt.type == PacketType.ACK) {
            routeAck(next, except = from)
        } else {
            scheduleForward(next, except = from, delayMs = Random.nextLong(50, 300))
        }
    }

    fun routeAck(ack: Packet, except: String?) {
        val refId = PacketSerializer.extractRefMsgId(ack.payload)
        val hop = refId?.let { breadcrumbs[it] }

        if (hop != null && hop != except && transport.isConnected(hop)) {
            // Targeted reverse hop
            transport.send(hop, ack.toWireBytes())
        } else {
            // Fallback flood within TTL
            transport.broadcast(ack.toWireBytes(), exceptNode = except)
        }
    }

    fun sendSos(triage: TriagePayload, lat: Double, lon: Double, accuracy: Float): Packet {
        val now = System.currentTimeMillis()
        val base = Packet(
            msgId = java.util.UUID.randomUUID().toString(),
            type = PacketType.SOS,
            priority = 0, // Highest priority
            senderKeyHash = myNodeId,
            senderCounter = System.currentTimeMillis(), // Monotonic counter
            dest = "ALL",
            createdAt = now,
            expiresAt = now + (72L * 3600 * 1000), // 72 hours
            lat = lat,
            lon = lon,
            accuracy = accuracy,
            payload = PacketSerializer.serializeTriage(triage),
            ttl = 30,
            hopCount = 0
        )

        val signedPkt = try {
            crypto.sign(base)
        } catch (e: Exception) {
            // Emergency beats authenticity: mark UNVERIFIABLE
            base.copy(flags = base.flags or PacketFlags.UNVERIFIABLE, signature = null)
        }

        store.save(signedPkt)
        seen.add(signedPkt.msgId)
        transport.broadcast(signedPkt.toWireBytes())
        return signedPkt
    }

    fun sendAck(sosPacket: Packet, status: String): Packet {
        val now = System.currentTimeMillis()
        val ack = Packet(
            msgId = java.util.UUID.randomUUID().toString(),
            type = PacketType.ACK,
            priority = 1,
            senderKeyHash = myNodeId,
            senderCounter = System.currentTimeMillis(),
            dest = sosPacket.senderKeyHash, // Destined to the victim node
            createdAt = now,
            expiresAt = now + (72L * 3600 * 1000),
            lat = 0.0,
            lon = 0.0,
            accuracy = 0f,
            payload = PacketSerializer.serializeAck(AckPayload(sosPacket.msgId, status)),
            ttl = 30,
            hopCount = 0
        )
        val signedAck = crypto.sign(ack)
        store.save(signedAck)
        store.markAcknowledged(sosPacket.msgId)
        routeAck(signedAck, except = null)
        return signedAck
    }

    private fun scheduleForward(pkt: Packet, except: String?, delayMs: Long) {
        executor.schedule({
            // Check if packet was acknowledged while waiting in forward queue
            if (pkt.type == PacketType.SOS && store.isAcknowledged(pkt.msgId)) {
                return@schedule
            }
            transport.broadcast(pkt.toWireBytes(), exceptNode = except)
            metrics.packetRelayed(pkt.msgId, pkt.hopCount)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun isExpired(pkt: Packet): Boolean {
        val now = System.currentTimeMillis()
        val gracePeriod = 24L * 3600 * 1000 // 24-hr grace
        return now > (pkt.expiresAt + gracePeriod)
    }

    private fun Packet.toWireBytes(): ByteArray = PacketSerializer.toWireBytes(this)
}

object PacketSerializer {
    fun toWireBytes(pkt: Packet): ByteArray {
        val json = """
            {
              "msgId": "${pkt.msgId}",
              "type": ${pkt.type.id},
              "priority": ${pkt.priority},
              "senderKeyHash": "${pkt.senderKeyHash}",
              "senderCounter": ${pkt.senderCounter},
              "dest": "${pkt.dest}",
              "createdAt": ${pkt.createdAt},
              "expiresAt": ${pkt.expiresAt},
              "lat": ${pkt.lat},
              "lon": ${pkt.lon},
              "accuracy": ${pkt.accuracy},
              "payload": ${pkt.payload},
              "signature": ${if (pkt.signature != null) "\"${pkt.signature}\"" else "null"},
              "flags": ${pkt.flags},
              "ttl": ${pkt.ttl},
              "hopCount": ${pkt.hopCount}
            }
        """.trimIndent()
        return json.toByteArray(Charsets.UTF_8)
    }

    fun fromWireBytes(bytes: ByteArray): Packet? {
        return try {
            val s = String(bytes, Charsets.UTF_8)
            // Lightweight regex parser for JVM/Android compatibility without mandatory jackson
            fun str(k: String): String = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(s)?.groupValues?.get(1) ?: ""
            fun num(k: String): Long = Regex("\"$k\"\\s*:\\s*(-?[0-9]+)").find(s)?.groupValues?.get(1)?.toLong() ?: 0L
            fun dbl(k: String): Double = Regex("\"$k\"\\s*:\\s*(-?[0-9.]+)").find(s)?.groupValues?.get(1)?.toDouble() ?: 0.0

            val pPayloadMatch = Regex("\"payload\"\\s*:\\s*(\\{[^}]+\\})").find(s)
            val payload = pPayloadMatch?.groupValues?.get(1) ?: "{}"
            val sigMatch = Regex("\"signature\"\\s*:\\s*\"([^\"]+)\"").find(s)
            val sig = sigMatch?.groupValues?.get(1)

            Packet(
                msgId = str("msgId"),
                type = PacketType.fromId(num("type").toInt()),
                priority = num("priority").toInt(),
                senderKeyHash = str("senderKeyHash"),
                senderCounter = num("senderCounter"),
                dest = str("dest").ifEmpty { "ALL" },
                createdAt = num("createdAt"),
                expiresAt = num("expiresAt"),
                lat = dbl("lat"),
                lon = dbl("lon"),
                accuracy = dbl("accuracy").toFloat(),
                payload = payload,
                signature = sig,
                flags = num("flags").toInt(),
                ttl = num("ttl").toInt(),
                hopCount = num("hopCount").toInt()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun serializeTriage(t: TriagePayload): String =
        """{"category":"${t.category}","severity":"${t.severity}","peopleCount":${t.peopleCount},"batteryPct":${t.batteryPct}}"""

    fun serializeAck(a: AckPayload): String =
        """{"refMsgId":"${a.refMsgId}","status":"${a.status}"}"""

    fun extractRefMsgId(payload: String): String? =
        Regex("\"refMsgId\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
}
