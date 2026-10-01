package com.meshline.core

/**
 * Per-sender anti-replay sliding window (64-entry bitmap).
 * Section 9 and Appendix B of MESHLiNE Implementation Plan Rev. 3.
 */
class ReplayWindow(
    var highest: Long = 0,
    var bitmap: Long = 0
) {
    @Synchronized
    fun accept(counter: Long): Boolean {
        if (counter > highest) {
            val shift = counter - highest
            bitmap = if (shift >= 64) 0 else bitmap shl shift.toInt()
            bitmap = bitmap or 1L
            highest = counter
            return true
        }
        val offset = highest - counter
        if (offset >= 64) return false // too old
        val bit = 1L shl offset.toInt()
        if ((bitmap and bit) != 0L) return false // already seen / replayed
        bitmap = bitmap or bit
        return true
    }
}
