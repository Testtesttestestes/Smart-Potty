package com.example.ml

import com.example.data.EventTypes
import com.example.data.TrackerEvent
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.pow

/**
 * Turns the raw event log into clean inter-void intervals for the models:
 * merges split potty sessions, separates night/sleep intervals, attaches fluid intake
 * and recency weights.
 */
object IntervalExtractor {

    const val MERGE_WINDOW_MS = 10 * 60_000L
    /** Without any sleep information, intervals longer than this are treated as night. */
    const val MAX_DAY_INTERVAL_MS = 6 * 60 * 60_000L
    const val RECENCY_HALF_LIFE_DAYS = 14.0
    private const val DAY_MS = 24 * 60 * 60_000L

    data class Void(
        val timestamp: Long,
        val volumeMl: Double?,
        val isAccident: Boolean,
        /** First void after a sleep period (ICCS excludes it from capacity estimation). */
        val isFirstAfterSleep: Boolean
    )

    data class Interval(
        val startTs: Long,
        val endTs: Long,
        val endVolumeMl: Double?,
        val intakeMl: Double,
        /** The interval starts with the first void after waking up. */
        val startsAfterWake: Boolean,
        val isNight: Boolean,
        val weight: Double,
        /** Minutes held after a "потерпи" command inside this interval (0 if none). */
        val heldMin: Double = 0.0
    ) {
        val durationMs: Long get() = endTs - startTs
        val durationMin: Double get() = durationMs / 60_000.0
    }

    data class SleepPeriod(val start: Long, val end: Long)

    data class Result(
        val voids: List<Void>,
        /** All intervals, including night ones (flagged). */
        val intervals: List<Interval>,
        val sleepPeriods: List<SleepPeriod>,
        val isAsleepNow: Boolean,
        /** Fluid intake since the last void, ml. */
        val intakeSinceLastVoidMl: Double,
        val lastVoidStartsAfterWake: Boolean
    ) {
        val dayIntervals: List<Interval> get() = intervals.filter { !it.isNight }
        val lastVoid: Void? get() = voids.lastOrNull()
    }

    fun recencyWeight(ageMs: Long): Double =
        0.5.pow(ageMs.coerceAtLeast(0L) / (RECENCY_HALF_LIFE_DAYS * DAY_MS))

    fun extract(
        events: List<TrackerEvent>,
        sleepSchedule: String?,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result {
        val sorted = events.filter { it.timestamp <= now }.sortedBy { it.timestamp }

        // 1. Sleep periods: explicit sleep/wake events win; otherwise the profile schedule.
        val explicit = sleepFromEvents(sorted, now)
        val periods = explicit.periods.ifEmpty {
            val first = sorted.firstOrNull()?.timestamp ?: now
            sleepFromSchedule(sleepSchedule, first - DAY_MS, now + DAY_MS, timeZone)
        }
        val hasSleepInfo = periods.isNotEmpty()
        val asleepNow = explicit.asleepNow ||
            (explicit.periods.isEmpty() && periods.any { now >= it.start && now < it.end })

        // 2. Voids, merging events of the same potty session.
        val rawVoids = sorted.filter { it.eventType == EventTypes.PEE }
        val merged = mutableListOf<Void>()
        var lastRawTs = Long.MIN_VALUE
        for (e in rawVoids) {
            val vol = (e.voidedVolumeMl ?: e.volumeLevel)?.toDouble()
            val prev = merged.lastOrNull()
            if (prev != null && e.timestamp - lastRawTs <= MERGE_WINDOW_MS) {
                val sum = if (prev.volumeMl == null && vol == null) null else (prev.volumeMl ?: 0.0) + (vol ?: 0.0)
                merged[merged.size - 1] = prev.copy(volumeMl = sum, isAccident = prev.isAccident || e.isAccident)
            } else {
                merged.add(Void(e.timestamp, vol, e.isAccident, isFirstAfterSleep = false))
            }
            lastRawTs = e.timestamp
        }

        val drinks = sorted.filter { it.eventType == EventTypes.DRINK && it.fluidIntakeMl != null }
        fun intakeBetween(from: Long, to: Long): Double =
            drinks.filter { it.timestamp >= from && it.timestamp < to }.sumOf { it.fluidIntakeMl!!.toDouble() }
        fun overlapsSleep(from: Long, to: Long) = periods.any { it.start < to && it.end > from }
        val commands = sorted.filter { it.eventType == EventTypes.COMMAND }
        fun heldIn(from: Long, to: Long): Double =
            commands.lastOrNull { it.timestamp > from && it.timestamp < to }?.let { (to - it.timestamp) / 60_000.0 } ?: 0.0

        // 3. Intervals.
        val voids = merged.toMutableList()
        val intervals = mutableListOf<Interval>()
        var nextStartsAfterWake = false
        for (i in 1 until voids.size) {
            val a = voids[i - 1]
            val b = voids[i]
            val duration = b.timestamp - a.timestamp
            val night = overlapsSleep(a.timestamp, b.timestamp) ||
                (!hasSleepInfo && duration > MAX_DAY_INTERVAL_MS)
            if (night) voids[i] = b.copy(isFirstAfterSleep = true)
            intervals.add(
                Interval(
                    startTs = a.timestamp,
                    endTs = b.timestamp,
                    endVolumeMl = b.volumeMl,
                    intakeMl = intakeBetween(a.timestamp, b.timestamp),
                    startsAfterWake = nextStartsAfterWake,
                    isNight = night,
                    weight = recencyWeight(now - b.timestamp),
                    heldMin = heldIn(a.timestamp, b.timestamp)
                )
            )
            nextStartsAfterWake = night
        }

        val last = voids.lastOrNull()
        return Result(
            voids = voids,
            intervals = intervals,
            sleepPeriods = periods,
            isAsleepNow = asleepNow,
            intakeSinceLastVoidMl = if (last != null) intakeBetween(last.timestamp, now + 1) else 0.0,
            lastVoidStartsAfterWake = nextStartsAfterWake
        )
    }

    private data class ExplicitSleep(val periods: List<SleepPeriod>, val asleepNow: Boolean)

    private fun sleepFromEvents(sorted: List<TrackerEvent>, now: Long): ExplicitSleep {
        val periods = mutableListOf<SleepPeriod>()
        var sleepStart: Long? = null
        for (e in sorted) {
            when (e.eventType) {
                EventTypes.SLEEP -> if (sleepStart == null) sleepStart = e.timestamp
                EventTypes.WAKE -> {
                    sleepStart?.let { periods.add(SleepPeriod(it, e.timestamp)) }
                    sleepStart = null
                }
            }
        }
        val open = sleepStart
        if (open != null) periods.add(SleepPeriod(open, now))
        return ExplicitSleep(periods, asleepNow = open != null)
    }

    private val SCHEDULE_REGEX = Regex("""(\d{1,2})[:.](\d{2})\s*[-–—]\s*(\d{1,2})[:.](\d{2})""")

    /** Parses "HH:mm-HH:mm" (e.g. "21:00-07:30"); returns start/end minutes of day or null. */
    fun parseSchedule(schedule: String?): Pair<Int, Int>? {
        val m = SCHEDULE_REGEX.find(schedule ?: return null) ?: return null
        val (h1, m1, h2, m2) = m.destructured
        val start = h1.toInt() * 60 + m1.toInt()
        val end = h2.toInt() * 60 + m2.toInt()
        if (start >= 24 * 60 || end >= 24 * 60 || start == end) return null
        return start to end
    }

    fun sleepFromSchedule(schedule: String?, from: Long, to: Long, timeZone: TimeZone): List<SleepPeriod> {
        val (startMin, endMin) = parseSchedule(schedule) ?: return emptyList()
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val result = mutableListOf<SleepPeriod>()
        while (cal.timeInMillis <= to) {
            val dayStart = cal.timeInMillis
            val start = atMinute(timeZone, dayStart, startMin, 0)
            val end = atMinute(timeZone, dayStart, endMin, if (endMin <= startMin) 1 else 0)
            result.add(SleepPeriod(start, end))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return result
    }

    private fun atMinute(tz: TimeZone, dayStart: Long, minuteOfDay: Int, plusDays: Int): Long =
        Calendar.getInstance(tz).apply {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_MONTH, plusDays)
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
        }.timeInMillis
}
