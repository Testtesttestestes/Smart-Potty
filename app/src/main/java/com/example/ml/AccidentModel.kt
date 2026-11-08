package com.example.ml

import com.example.data.EventTypes
import com.example.data.TrackerEvent

/**
 * Leaks vs. accidents, and volumes of accidents that were not measured.
 *
 * A leak (laughing, coughing...) does not empty the bladder, a real accident does. The potty
 * settles it after the fact: if the next potty volume is close to what a bladder that kept
 * filling since the last real void would hold, the event was a leak; if it is much smaller,
 * the bladder had been emptied — it was an accident.
 */
object AccidentModel {

    enum class LeakVerdict { LEAK, EMPTIED, UNCERTAIN }

    data class LeakResult(val eventId: String, val verdict: LeakVerdict, val ratio: Double?)

    /** Next potty volume ≥ this × expected → it was only a leak. */
    private const val LEAK_RATIO = 0.6
    /** Next potty volume ≤ this × expected → the bladder had been emptied. */
    private const val EMPTIED_RATIO = 0.4
    private const val MAX_ACCIDENT_FILL = 1.2

    fun classifyLeaks(
        events: List<TrackerEvent>,
        extraction: IntervalExtractor.Result,
        fillRateMlPerMin: Double?
    ): List<LeakResult> {
        val leaks = events.filter { it.eventType == EventTypes.LEAK }.sortedBy { it.timestamp }
        return leaks.map { leak ->
            val prev = extraction.voids.lastOrNull { it.timestamp < leak.timestamp }
            val next = extraction.voids.firstOrNull { it.timestamp > leak.timestamp }
            if (fillRateMlPerMin == null || prev == null || next == null || next.isAccident ||
                next.volumeMl == null || next.isFirstAfterSleep
            ) {
                return@map LeakResult(leak.eventId, LeakVerdict.UNCERTAIN, null)
            }
            val expected = fillRateMlPerMin * (next.timestamp - prev.timestamp) / 60_000.0
            val ratio = next.volumeMl / expected
            val verdict = when {
                ratio >= LEAK_RATIO -> LeakVerdict.LEAK
                ratio <= EMPTIED_RATIO -> LeakVerdict.EMPTIED
                else -> LeakVerdict.UNCERTAIN
            }
            LeakResult(leak.eventId, verdict, ratio)
        }
    }

    /**
     * Leaks found to have emptied the bladder become accident voids (estimated volume), so the
     * interval clock restarts there.
     */
    fun withEmptyingLeaksAsAccidents(events: List<TrackerEvent>, results: List<LeakResult>): List<TrackerEvent> {
        val emptied = results.filter { it.verdict == LeakVerdict.EMPTIED }.map { it.eventId }.toSet()
        if (emptied.isEmpty()) return events
        return events.map {
            if (it.eventId in emptied) it.copy(eventType = EventTypes.PEE, isAccident = true, volumeEstimated = true) else it
        }
    }

    /** Estimated volume of an accident: what the bladder held, ≤ 1.2 × capacity. */
    fun estimateAccidentVolumeMl(
        accidentTs: Long,
        extraction: IntervalExtractor.Result,
        bladder: BladderModel.Estimate
    ): Double? {
        val rate = bladder.fillRateMlPerMin ?: return null
        val prev = extraction.voids.lastOrNull { it.timestamp < accidentTs } ?: return null
        val ml = rate * (accidentTs - prev.timestamp) / 60_000.0
        return ml.coerceAtMost(MAX_ACCIDENT_FILL * bladder.personalCapacityMl)
    }
}
