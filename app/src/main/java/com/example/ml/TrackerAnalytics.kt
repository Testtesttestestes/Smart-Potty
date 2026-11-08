package com.example.ml

import com.example.data.Child
import com.example.data.TrackerEvent
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow

/** One place that turns the event log into everything the tracker screen shows. */
object TrackerAnalytics {

    /** Alert ("потерпи") when P(void within the next 5 min | not yet, current signals) reaches this. */
    const val COMMAND_THRESHOLD = 0.5f

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
        val calibration: Calibration,
        val isAsleepNow: Boolean,
        val dayIntervalCount: Int,
        val intakeSinceLastVoidMl: Double,
        val ageMonths: Double?
    ) {
        val shouldAlert: Boolean
            get() = !isAsleepNow && model.hasLastVoid && probNext5 >= COMMAND_THRESHOLD
    }

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

    fun learn(events: List<TrackerEvent>, child: Child?, now: Long, timeZone: TimeZone = TimeZone.getDefault()): Learned {
        val age = ageMonths(child?.birthDate, now, timeZone)
        val extraction = IntervalExtractor.extract(events, child?.sleepSchedule, now, timeZone)
        val reports = SignalModel.sensationReports(events)
        val post = BayesianSurvivalModel.fit(BayesianSurvivalModel.observationsFrom(extraction.intervals), age)
        val signal = SignalModel.fit(
            SignalModel.bins(extraction.intervals, reports, { post.predictive(featuresOf(it)) })
        )
        return Learned(signal, backtest(extraction.intervals, reports, age))
    }

    private fun featuresOf(iv: IntervalExtractor.Interval) =
        BayesianSurvivalModel.Features(iv.intakeMl, iv.startsAfterWake)

    fun compute(
        events: List<TrackerEvent>,
        child: Child?,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        learned: Learned = learn(events, child, now, timeZone)
    ): Snapshot {
        val age = ageMonths(child?.birthDate, now, timeZone)
        val extraction = IntervalExtractor.extract(events, child?.sleepSchedule, now, timeZone)
        val observations = BayesianSurvivalModel.observationsFrom(extraction.intervals)
        val last = extraction.lastVoid
        val model = BayesianSurvivalModel.analyze(
            observations,
            elapsedMillis = last?.let { now - it.timestamp },
            currentFeatures = BayesianSurvivalModel.Features(
                extraction.intakeSinceLastVoidMl, extraction.lastVoidStartsAfterWake
            ),
            ageMonths = age
        )
        val bladder = BladderModel.estimate(extraction, age, now, model.medianIntervalMillis / 60_000.0)
        val signal = if (last != null) SignalModel.signalAt(SignalModel.sensationReports(events), last.timestamp, now)
            else SignalModel.Signal.NONE
        val p5 = if (last != null) SignalModel.combine(model.probNext5.toDouble(), signal, learned.signal) else 0.0
        return Snapshot(
            model = model,
            probNext5 = p5.toFloat(),
            currentSignal = signal,
            signalCoefficients = learned.signal,
            bladder = bladder,
            interoception = InteroceptionMetrics.compute(events, extraction, bladder.personalCapacityMl, now, timeZone),
            calibration = learned.calibration,
            isAsleepNow = extraction.isAsleepNow,
            dayIntervalCount = observations.size,
            intakeSinceLastVoidMl = extraction.intakeSinceLastVoidMl,
            ageMonths = age
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
            val post = BayesianSurvivalModel.fit(BayesianSurvivalModel.observationsFrom(historyIntervals), ageMonths)
            val historyReports = reports.filter { it.timestamp < target.startTs }
            val coef = SignalModel.fit(SignalModel.bins(historyIntervals, historyReports, { post.predictive(featuresOf(it)) }))
            val pred = post.predictive(featuresOf(target))
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
