package com.meshline.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sybil and DoS mitigation rate limiter.
 * Limits messages per physical link (crucial Sybil defense) and per sender key.
 */
class RateLimiter {
    private val linkWindows = ConcurrentHashMap<String, RateBucket>()
    private val senderWindows = ConcurrentHashMap<String, RateBucket>()
    private val unsignedSosWindows = ConcurrentHashMap<String, RateBucket>()

    // Configurable rates per minute
    private val maxPerLinkPerMin = 60
    private val maxPerSenderPerMin = 20
    private val maxUnsignedPerLinkPerMin = 1 // Emergency beats authenticity fallback: max 1 per min per link

    fun allowLink(linkId: String): Boolean {
        val bucket = linkWindows.computeIfAbsent(linkId) { RateBucket() }
        return bucket.tryConsume(maxPerLinkPerMin, 60_000L)
    }

    fun allowSender(senderHash: String): Boolean {
        val bucket = senderWindows.computeIfAbsent(senderHash) { RateBucket() }
        return bucket.tryConsume(maxPerSenderPerMin, 60_000L)
    }

    fun allowUnsigned(linkId: String): Boolean {
        val bucket = unsignedSosWindows.computeIfAbsent(linkId) { RateBucket() }
        return bucket.tryConsume(maxUnsignedPerLinkPerMin, 60_000L)
    }

    private class RateBucket {
        private var windowStart = System.currentTimeMillis()
        private val count = AtomicInteger(0)

        @Synchronized
        fun tryConsume(limit: Int, windowMs: Long): Boolean {
            val now = System.currentTimeMillis()
            if (now - windowStart > windowMs) {
                windowStart = now
                count.set(0)
            }
            return count.incrementAndGet() <= limit
        }
    }
}
