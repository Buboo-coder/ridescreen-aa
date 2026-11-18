// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import kotlin.math.abs
import kotlin.math.max

/**
 * Decides how often the trip recorder asks the phone for GPS: every second normally, every two
 * seconds only while the ride has been a steady, straight cruise with a good signal for a while.
 *
 * Relaxing needs ALL of these for [STABLE_NEEDED_MS] in a row: speed of at least 50 km/h, speed
 * changing by at most 10 % per second, heading changing by at most 3° per second, accuracy of
 * 15 m or better, and regular fixes. The first fix that breaks any of them (a turn, braking,
 * slowing into town, a weak signal, a gap in fixes) goes straight back to every second, so city
 * riding is always recorded at full rate.
 *
 * Note: this only changes what *this app* asks for. If another app (Google Maps navigation, speed
 * volume) asks for 1 s, the phone's GPS keeps running at 1 s regardless.
 */
class GpsRatePolicy {
    var intervalMs: Long = FAST_MS
        private set

    private var prevTimeMs = 0L
    private var prevSpeed = 0f
    private var prevBearing = 0f
    private var havePrev = false
    private var stableSinceMs = 0L

    fun reset() {
        intervalMs = FAST_MS
        havePrev = false
        stableSinceMs = 0L
    }

    /**
     * Feed every accepted fix. Returns the new interval when it should change, otherwise null.
     * [timeMs] is any monotonic clock in milliseconds (e.g. the fix's elapsed-realtime).
     */
    fun onFix(
        timeMs: Long,
        hasSpeed: Boolean, speedMs: Float,
        hasBearing: Boolean, bearingDeg: Float,
        hasAccuracy: Boolean, accuracyM: Float,
    ): Long? {
        var stable = false
        if (havePrev && hasSpeed && hasBearing && hasAccuracy) {
            val dt = timeMs - prevTimeMs
            if (dt in MIN_DT_MS..MAX_DT_MS && speedMs >= MIN_SPEED_MS && prevSpeed >= MIN_SPEED_MS && accuracyM <= MAX_ACCURACY_M) {
                val dtS = dt / 1000f
                val speedChangePerS = abs(speedMs - prevSpeed) / max(prevSpeed, 1f) / dtS
                val turnPerS = angleDiff(bearingDeg, prevBearing) / dtS
                stable = speedChangePerS <= MAX_SPEED_CHANGE_PER_S && turnPerS <= MAX_TURN_DEG_PER_S
            }
        }
        // Remember this fix for the next comparison (only meaningful when we have the data).
        if (hasSpeed && hasBearing) {
            prevTimeMs = timeMs; prevSpeed = speedMs; prevBearing = bearingDeg; havePrev = true
        } else {
            havePrev = false
        }

        if (!stable) {
            stableSinceMs = 0L
            if (intervalMs != FAST_MS) { intervalMs = FAST_MS; return FAST_MS }
            return null
        }
        if (stableSinceMs == 0L) stableSinceMs = timeMs
        if (intervalMs == FAST_MS && timeMs - stableSinceMs >= STABLE_NEEDED_MS) {
            intervalMs = RELAXED_MS
            return RELAXED_MS
        }
        return null
    }

    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    companion object {
        const val FAST_MS = 1000L
        const val RELAXED_MS = 2000L
        const val STABLE_NEEDED_MS = 30_000L
        const val MIN_SPEED_MS = 13.9f          // 50 km/h
        const val MAX_SPEED_CHANGE_PER_S = 0.10f
        const val MAX_TURN_DEG_PER_S = 3f
        const val MAX_ACCURACY_M = 15f
        private const val MIN_DT_MS = 500L
        private const val MAX_DT_MS = 4000L
    }
}
