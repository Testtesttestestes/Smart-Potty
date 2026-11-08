package com.example.ml

import com.example.data.FlowPatterns

/**
 * Turns the potty scale's timestamped weight readings during one session into a flow summary
 * (home uroflowmetry): stream start/end, volume, peak flow and the curve shape.
 *
 * A smooth single stream means the child was relaxed; a stream that stops and restarts
 * ("interrupted") suggests tension or shyness, so such voids are not counted against
 * interoception.
 */
object UroflowAnalyzer {

    data class Sample(val timestampMs: Long, val grams: Double)

    data class FlowSummary(
        val streamStartMs: Long,
        val streamEndMs: Long,
        val volumeMl: Double,
        val maxFlowMlPerSec: Double?,
        val avgFlowMlPerSec: Double?,
        val pattern: String,
        val sampleRateHz: Double
    ) {
        val durationMs: Long get() = streamEndMs - streamStartMs
    }

    /** Below this sample rate the curve shape cannot be judged. */
    const val MIN_RATE_FOR_SHAPE_HZ = 4.0
    private const val START_THRESHOLD_G = 3.0
    private const val SMOOTH_WINDOW_MS = 500L
    private const val PAUSE_MIN_MS = 700L
    private const val PAUSE_FRACTION_OF_PEAK = 0.1

    fun analyze(samples: List<Sample>): FlowSummary? {
        if (samples.size < 2) return null
        val sorted = samples.sortedBy { it.timestampMs }
        val baseline = sorted.first().grams.coerceAtMost(sorted.take(3).minOf { it.grams })
        // Running maximum suppresses jitter from the child shifting on the seat.
        var runMax = Double.NEGATIVE_INFINITY
        val env = sorted.map { s -> runMax = maxOf(runMax, s.grams - baseline); Sample(s.timestampMs, runMax) }

        val start = env.firstOrNull { it.grams >= START_THRESHOLD_G } ?: return null
        val total = env.last().grams
        // Stream end: first time the envelope gets within 1 g of the final volume.
        val end = env.first { it.timestampMs >= start.timestampMs && it.grams >= total - 1.0 }
        val volumeMl = total / BladderModel.URINE_DENSITY_G_PER_ML

        val span = (sorted.last().timestampMs - sorted.first().timestampMs).coerceAtLeast(1)
        val rate = (sorted.size - 1) * 1000.0 / span
        val streamSec = (end.timestampMs - start.timestampMs) / 1000.0
        val avg = if (streamSec > 0) volumeMl / streamSec else null

        if (rate < MIN_RATE_FOR_SHAPE_HZ) {
            return FlowSummary(start.timestampMs, end.timestampMs, volumeMl, null, avg, FlowPatterns.UNKNOWN, rate)
        }

        // Flow = slope of the envelope over a sliding window.
        val inStream = env.filter { it.timestampMs in start.timestampMs..end.timestampMs }
        val flows = inStream.map { s ->
            val from = env.lastOrNull { it.timestampMs <= s.timestampMs - SMOOTH_WINDOW_MS } ?: env.first()
            val dt = (s.timestampMs - from.timestampMs) / 1000.0
            s.timestampMs to if (dt > 0) (s.grams - from.grams) / BladderModel.URINE_DENSITY_G_PER_ML / dt else 0.0
        }
        val peak = flows.maxOfOrNull { it.second } ?: 0.0

        // Pauses: stretches inside the stream where flow nearly stops for ≥ PAUSE_MIN_MS.
        var pauses = 0
        var lowSince: Long? = null
        for ((ts, f) in flows) {
            if (f < PAUSE_FRACTION_OF_PEAK * peak) {
                if (lowSince == null) lowSince = ts
            } else {
                if (lowSince != null && ts - lowSince >= PAUSE_MIN_MS) pauses++
                lowSince = null
            }
        }
        val pattern = if (pauses >= 1) FlowPatterns.INTERRUPTED else FlowPatterns.BELL
        return FlowSummary(start.timestampMs, end.timestampMs, volumeMl, peak, avg, pattern, rate)
    }
}
