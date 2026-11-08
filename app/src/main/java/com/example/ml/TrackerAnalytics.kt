package com.example.ml

import com.example.data.Child
import com.example.data.EventTypes
import com.example.data.TrackerEvent
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow

/** One place that turns the event log into everything the tracker screen shows. */
object TrackerAnalytics {

    data class Calibration(
        /** Number of back-tested checkpoints. */
        val n: Int,
        val brier: Double?,
        /** Brier skill vs. always predicting the base rate (> 0 means better than the baseline). */
        val skill: Double?,
        /** (mean predicted, observed frequency, count) per probability bin. */
        val bins: List<Triple<Double, Double, Int>>
    )

    /** Parts that depend only on the event log (not on the clock) — computed once per data change. */
    data class Learned(
        val signal: SignalModel.Coefficients,
        val calibration: Calibration
    )

    data class Snapshot(
        val model: BayesianSurvivalModel.ModelResult,
        /** P(void in the next 5 min | no void yet), timing + current sensation/holding signals. */
        val probNext5: Float,
        val currentSignal: SignalModel.Signal,
        val signalCoefficients: SignalModel.Coefficients,
        val bladder: BladderModel.Estimate,
        val interoception: InteroceptionMetrics.Result,
        val success: InteroSuccess.Result,
        val calibration: Calibration,
        val leaks: List<AccidentModel.LeakResult>,
        /** Estimated volumes of accidents that were not measured, by event id. */
        val accidentVolumeEstimates: Map<String, Double>,
        val staircase: Coaching.Staircase,
        val decision: Coaching.Decision,
        val settings: Coaching.Settings,
        /** End of the currently running "потерпи" countdown, if any. */
        val activeHoldReleaseTs: Long?,
        val isAsleepNow: Boolean,
        val dayIntervalCount: Int,
        val intakeSinceLastVoidMl: Double,
        val ageMonths: Double?
    )

    fun ageMonths(birthDate: String?, now: Long, timeZone: TimeZone = TimeZone.getDefault()): Double? {
        if (birthDate.isNullOrBlank()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone; isLenient = false }
            val born = fmt.parse(birthDate.trim())?.time ?: return null
            ((now - born) / (30.4375 * 24 * 60 * 60_000.0)).takeIf { it >= 0 }
        } catch (e: Exception) {
            null
        }
    }

    private data class Prepared(
        val age: Double?,
        /** Events with leaks that emptied the bladder turned into accident voids. */
        val events: List<TrackerEvent>,
        val extraction: IntervalExtractor.Result,
        val leaks: List<AccidentModel.LeakResult>
    )

    private fun prepare(events: List<TrackerEvent>, child: Child?, now: Long, timeZone: TimeZone): Prepared {
        val age = ageMonths(child?.birthDate, now, timeZone)
        val ex0 = IntervalExtractor.extract(events, child?.sleepSchedule, now, timeZone)
        val leaks = if (events.any { it.eventType == EventTypes.LEAK }) {
            val rate = BladderModel.estimate(ex0, age, now, BayesianSurvivalModel.priorMedianMinutes(age)).fillRateMlPerMin
            AccidentModel.classifyLeaks(events, ex0, rate)
        } else emptyList()
        val effective = AccidentModel.withEmptyingLeaksAsAccidents(events, leaks)
        val ex = if (effective === events) ex0 else IntervalExtractor.extract(effective, child?.sleepSchedule, now, timeZone)
        return Prepared(age, effective, ex, leaks)
    }

    fun learn(events: List<TrackerEvent>, child: Child?, now: Long, timeZone: TimeZone = TimeZone.getDefault()): Learned {
        val p = prepare(events, child, now, timeZone)
        val reports = SignalModel.sensationReports(p.events)
        val post = BayesianSurvivalModel.fit(BayesianSurvivalModel.observationsFrom(p.extraction.intervals, now), p.age)
        val signal = SignalModel.fit(
            SignalModel.bins(p.extraction.intervals, reports, { post.predictive(BayesianSurvivalModel.featuresOf(it, now)) })
        )
        return Learned(signal, backtest(p.extraction.intervals, reports, p.age))
    }

    fun compute(
        events: List<TrackerEvent>,
        child: Child?,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        learned: Learned = learn(events, child, now, timeZone),
        settings: Coaching.Settings = Coaching.Settings()
    ): Snapshot {
        val p = prepare(events, child, now, timeZone)
        val extraction = p.extraction
        val observations = BayesianSurvivalModel.observationsFrom(extraction.intervals, now)
        val last = extraction.lastVoid
        val model = BayesianSurvivalModel.analyze(
            observations,
            elapsedMillis = last?.let { now - it.timestamp },
            currentFeatures = BayesianSurvivalModel.Features(
                extraction.intakeSinceLastVoidMl, extraction.lastVoidStartsAfterWake
            ),
            ageMonths = p.age
        )
        val bladder = BladderModel.estimate(extraction, p.age, now, model.medianIntervalMillis / 60_000.0)
        val signal = if (last != null) SignalModel.signalAt(SignalModel.sensationReports(p.events), last.timestamp, now)
            else SignalModel.Signal.NONE
        val p5 = if (last != null) SignalModel.combine(model.probNext5.toDouble(), signal, learned.signal) else 0.0

        val accidentEstimates = p.events
            .filter { it.eventType == EventTypes.PEE && it.isAccident && it.voidedVolumeMl == null && it.volumeLevel == null }
            .mapNotNull { e -> AccidentModel.estimateAccidentVolumeMl(e.timestamp, extraction, bladder)?.let { e.eventId to it } }
            .toMap()
        val success = InteroSuccess.compute(p.events, extraction, bladder.personalCapacityMl, now, timeZone)
        val staircase = Coaching.staircase(events, settings)
        val activeHold = events.filter {
            it.eventType == EventTypes.COMMAND && (last == null || it.timestamp > last.timestamp)
        }.maxByOrNull { it.timestamp }?.takeIf { Coaching.holdOutcome(it, events) == Coaching.HoldOutcome.PENDING }
        val decision = Coaching.decide(
            settings, p5, signal, extraction.isAsleepNow, last != null, success.recentSuccessRate, staircase, bladder
        ).let { if (activeHold != null) it.copy(action = Coaching.Action.NONE) else it }

        return Snapshot(
            model = model,
            probNext5 = p5.toFloat(),
            currentSignal = signal,
            signalCoefficients = learned.signal,
            bladder = bladder,
            interoception = InteroceptionMetrics.compute(p.events, extraction, bladder.personalCapacityMl, now, timeZone),
            success = success,
            calibration = learned.calibration,
            leaks = p.leaks,
            accidentVolumeEstimates = accidentEstimates,
            staircase = staircase,
            decision = decision,
            settings = settings,
            activeHoldReleaseTs = activeHold?.let { it.timestamp + (it.holdTargetMin ?: 0) * 60_000L },
            isAsleepNow = extraction.isAsleepNow,
            dayIntervalCount = observations.size,
            intakeSinceLastVoidMl = extraction.intakeSinceLastVoidMl,
            ageMonths = p.age
        )
    }

    /**
     * Honest calibration without stored predictions: for each of the most recent daytime
     * intervals, fit the timing model and the signal layer only on intervals that ended before
     * it started, then score P(void within 5 min) every 5 minutes along it.
     */
    fun backtest(
        intervals: List<IntervalExtractor.Interval>,
        reports: List<TrackerEvent>,
        ageMonths: Double?,
        maxIntervals: Int = 40,
        minHistory: Int = 3
    ): Calibration {
        val day = intervals.filter { !it.isNight }
        val preds = mutableListOf<Pair<Double, Boolean>>()
        val start = (day.size - maxIntervals).coerceAtLeast(minHistory)
        for (k in start until day.size) {
            val target = day[k]
            val historyIntervals = day.subList(0, k).filter { it.endTs <= target.startTs }
                .map { it.copy(weight = IntervalExtractor.recencyWeight(target.startTs - it.endTs)) }
            if (historyIntervals.size < minHistory) continue
            val post = BayesianSurvivalModel.fit(BayesianSurvivalModel.observationsFrom(historyIntervals, target.startTs), ageMonths)
            val historyReports = reports.filter { it.timestamp < target.startTs }
            val coef = SignalModel.fit(SignalModel.bins(historyIntervals, historyReports, {
                post.predictive(BayesianSurvivalModel.featuresOf(it, target.startTs))
            }))
            // At prediction time the outcome of a later hold command is unknown → heldMin = 0.
            val pred = post.predictive(BayesianSurvivalModel.featuresOf(target, target.endTs).copy(heldMin = 0.0))
            for (bin in SignalModel.bins(listOf(target), reports, { pred })) {
                preds.add(SignalModel.combine(bin.baseline, bin.signal, coef) to bin.label)
            }
        }
        if (preds.isEmpty()) return Calibration(0, null, null, emptyList())
        val brier = preds.sumOf { (p, y) -> (p - if (y) 1.0 else 0.0).pow(2) } / preds.size
        val base = preds.count { it.second }.toDouble() / preds.size
        val brierBase = preds.sumOf { (_, y) -> (base - if (y) 1.0 else 0.0).pow(2) } / preds.size
        val bins = (0 until 4).mapNotNull { b ->
            val inBin = preds.filter { (p, _) -> (p * 4).toInt().coerceAtMost(3) == b }
            if (inBin.isEmpty()) null else Triple(
                inBin.map { it.first }.average(),
                inBin.count { it.second }.toDouble() / inBin.size,
                inBin.size
            )
        }
        return Calibration(
            n = preds.size,
            brier = brier,
            skill = if (brierBase > 0) 1.0 - brier / brierBase else null,
            bins = bins
        )
    }
}
