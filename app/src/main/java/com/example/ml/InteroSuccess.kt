package com.example.ml

import com.example.data.EventTypes
import com.example.data.FlowPatterns
import com.example.data.Initiators
import com.example.data.TrackerEvent
import java.util.Calendar
import java.util.TimeZone

/**
 * The ideal void for interoception training: the child went on their own, the stream started
 * right away, the bladder was really full, and there was no accident before.
 *
 * Latency is judged against the child's own norm at high fill, not a fixed number, and an
 * interrupted flow (tension, shyness) or a known difficult context is never counted as a
 * failure of interoception.
 */
object InteroSuccess {

    const val MIN_FILL = 0.6
    private const val DEFAULT_LATENCY_OK_MS = 30_000L
    private const val SIT_WINDOW_MS = 10 * 60_000L
    private const val EARLY_SIT_MS = 3 * 60_000L
    private const val DIFFICULT_RELAX_MEDIAN_MS = 60_000L
    private const val TREND_WINDOW_MS = 14L * 24 * 60 * 60_000L

    data class VoidScore(
        val eventId: String,
        val timestamp: Long,
        val initiator: String,
        val fill: Double?,
        val latencyMs: Long?,
        val flowPattern: String?,
        val success: Boolean
    )

    data class LatencyNorm(val medianMs: Long, val p75Ms: Long, val n: Int)

    data class Week(
        val year: Int, val week: Int,
        val voids: Int, val successes: Int, val accidents: Int, val leaks: Int, val earlySits: Int
    ) {
        val successRate: Double? get() = if (voids + accidents == 0) null else successes.toDouble() / (voids + accidents)
    }

    data class Result(
        val scores: List<VoidScore>,
        val latencyNorm: LatencyNorm?,
        /** Long latency even with a full bladder: trouble relaxing, not a lack of interoception. */
        val difficultyRelaxing: Boolean,
        val weeks: List<Week>,
        /** Share of interoceptive successes over the last 14 days (null without data). */
        val recentSuccessRate: Double?
    )

    /** Latency from the latest sit-down before the void (seat sensor), if not stored on the event. */
    fun latencyOf(pee: TrackerEvent, events: List<TrackerEvent>): Long? {
        pee.latencyMs?.let { return it }
        val sit = events.lastOrNull {
            it.eventType == EventTypes.SIT && it.timestamp <= pee.timestamp && pee.timestamp - it.timestamp <= SIT_WINDOW_MS
        } ?: return null
        return pee.timestamp - sit.timestamp
    }

    fun compute(
        events: List<TrackerEvent>,
        extraction: IntervalExtractor.Result,
        capacityMl: Double,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result {
        val sorted = events.sortedBy { it.timestamp }
        val pees = sorted.filter { it.eventType == EventTypes.PEE }
        val nightVoids = extraction.voids.filter { it.isFirstAfterSleep }.map { it.timestamp }.toSet()

        data class Raw(val e: TrackerEvent, val fill: Double?, val latency: Long?, val initiator: String)
        val raw = pees.filter { !it.isAccident }.map { e ->
            val vol = (e.voidedVolumeMl ?: e.volumeLevel)?.toDouble()
            Raw(e, vol?.let { it / capacityMl }, latencyOf(e, sorted), Coaching.initiatorOf(e, sorted))
        }

        val normSample = raw.filter {
            it.latency != null && (it.fill ?: 0.0) >= MIN_FILL && it.e.flowPattern != FlowPatterns.INTERRUPTED
        }.map { it.latency!!.toDouble() }
        val norm = if (normSample.size >= 3) LatencyNorm(
            StatMath.quantile(normSample, 0.5)!!.toLong(), StatMath.quantile(normSample, 0.75)!!.toLong(), normSample.size
        ) else null
        val latencyLimit = maxOf(DEFAULT_LATENCY_OK_MS, norm?.p75Ms ?: 0L)

        val scores = raw.map { r ->
            val latencyOk = r.latency == null || r.e.flowPattern == FlowPatterns.INTERRUPTED || r.latency <= latencyLimit
            val success = r.initiator == Initiators.CHILD && (r.fill ?: 0.0) >= MIN_FILL && latencyOk &&
                r.e.timestamp !in nightVoids
            VoidScore(r.e.eventId, r.e.timestamp, r.initiator, r.fill, r.latency, r.e.flowPattern, success)
        }

        // Sat down and nothing came within a few minutes: went too early.
        val earlySits = sorted.filter { it.eventType == EventTypes.SIT }.filter { s ->
            pees.none { it.timestamp >= s.timestamp && it.timestamp - s.timestamp <= EARLY_SIT_MS }
        }

        fun weekKey(ts: Long): Pair<Int, Int> {
            val c = Calendar.getInstance(timeZone).apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4; timeInMillis = ts }
            return c.getWeekYear() to c.get(Calendar.WEEK_OF_YEAR)
        }
        val keys = (scores.map { weekKey(it.timestamp) } +
            pees.filter { it.isAccident }.map { weekKey(it.timestamp) } +
            sorted.filter { it.eventType == EventTypes.LEAK }.map { weekKey(it.timestamp) } +
            earlySits.map { weekKey(it.timestamp) }).toSortedSet(compareBy({ it.first }, { it.second }))
        val weeks = keys.map { k ->
            Week(
                k.first, k.second,
                voids = scores.count { weekKey(it.timestamp) == k },
                successes = scores.count { it.success && weekKey(it.timestamp) == k },
                accidents = pees.count { it.isAccident && weekKey(it.timestamp) == k },
                leaks = sorted.count { it.eventType == EventTypes.LEAK && weekKey(it.timestamp) == k },
                earlySits = earlySits.count { weekKey(it.timestamp) == k }
            )
        }

        val recentScores = scores.filter { now - it.timestamp <= TREND_WINDOW_MS }
        val recentAccidents = pees.count { it.isAccident && now - it.timestamp <= TREND_WINDOW_MS }
        val denom = recentScores.size + recentAccidents
        val recentRate = if (denom == 0) null else recentScores.count { it.success }.toDouble() / denom

        return Result(
            scores = scores,
            latencyNorm = norm,
            difficultyRelaxing = norm != null && norm.n >= 5 && norm.medianMs > DIFFICULT_RELAX_MEDIAN_MS,
            weeks = weeks,
            recentSuccessRate = recentRate
        )
    }
}
