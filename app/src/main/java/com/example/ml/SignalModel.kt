package com.example.ml

import com.example.data.EventTypes
import com.example.data.TrackerEvent
import kotlin.math.exp
import kotlin.math.ln

/**
 * Short-horizon layer on top of the survival model.
 *
 * Timing alone cannot be sharp over a 5-minute window (interval spread is tens of minutes),
 * so recent real-time signals — the child's reported sensation and the observed holding
 * behaviour — shift the baseline in logit space:
 *
 *   logit P(void in next h | no void yet, signals) = logit(p_baseline) + γ_s·sensation + γ_h·holding
 *
 * γ is fitted per child (MAP logistic regression over 5-minute bins of past intervals, with
 * the baseline as offset), starting from a moderate prior so it works before data exists.
 */
object SignalModel {

    /** Prediction horizon for alerts and stored probabilities. */
    const val HORIZON_MIN = 5.0
    /** A sensation/holding report counts as "current" for this long. */
    const val SIGNAL_WINDOW_MIN = 20.0

    // Prior: each sensation level ×1.6 odds, each holding level ×2 odds.
    private val PRIOR_MEAN = doubleArrayOf(0.5, 0.7)
    private const val PRIOR_SD = 0.6

    data class Signal(val sensation: Int, val holding: Int) {
        fun vector() = doubleArrayOf(sensation.toDouble(), holding.toDouble())
        companion object { val NONE = Signal(0, 0) }
    }

    data class Coefficients(val sensation: Double, val holding: Double, val bins: Int, val signalBins: Int) {
        fun logOdds(s: Signal) = sensation * s.sensation + holding * s.holding
        companion object { val PRIOR = Coefficients(PRIOR_MEAN[0], PRIOR_MEAN[1], 0, 0) }
    }

    data class Bin(val baseline: Double, val signal: Signal, val label: Boolean, val weight: Double)

    /** Latest sensation report in (afterTs, atTs], no older than the signal window. */
    fun signalAt(reports: List<TrackerEvent>, afterTs: Long, atTs: Long): Signal {
        val minTs = maxOf(afterTs, atTs - (SIGNAL_WINDOW_MIN * 60_000).toLong())
        val r = reports.lastOrNull { it.timestamp in (minTs + 1)..atTs } ?: return Signal.NONE
        return Signal(
            InteroceptionMetrics.levelIndex(r.sensationLevel) ?: 0,
            (r.holdingLevel ?: 0).coerceIn(0, 3)
        )
    }

    fun sensationReports(events: List<TrackerEvent>): List<TrackerEvent> =
        events.filter { it.eventType == EventTypes.SENSATION }.sortedBy { it.timestamp }

    fun combine(baseline: Double, signal: Signal, c: Coefficients): Double {
        val o = logit(baseline) + c.logOdds(signal)
        return 1.0 / (1.0 + exp(-o))
    }

    /** 5-minute bins along each interval, with the baseline from [predictive] as offset. */
    fun bins(
        intervals: List<IntervalExtractor.Interval>,
        reports: List<TrackerEvent>,
        predictive: (IntervalExtractor.Interval) -> BayesianSurvivalModel.Predictive,
        stepMin: Double = HORIZON_MIN
    ): List<Bin> {
        val out = mutableListOf<Bin>()
        for (iv in intervals) {
            if (iv.isNight) continue
            val pred = predictive(iv)
            var t = 0.0
            while (t < iv.durationMin) {
                val at = iv.startTs + (t * 60_000).toLong()
                out.add(
                    Bin(
                        baseline = pred.conditionalProbability(t, HORIZON_MIN),
                        signal = signalAt(reports, iv.startTs, at),
                        label = iv.durationMin - t <= HORIZON_MIN,
                        weight = iv.weight
                    )
                )
                t += stepMin
            }
        }
        return out
    }

    /** MAP fit of γ by Newton–Raphson with a Gaussian prior. */
    fun fit(bins: List<Bin>): Coefficients {
        val g = PRIOR_MEAN.copyOf()
        val precision = 1.0 / (PRIOR_SD * PRIOR_SD)
        repeat(25) {
            val grad = DoubleArray(2) { -(g[it] - PRIOR_MEAN[it]) * precision }
            val negH = Array(2) { i -> DoubleArray(2) { j -> if (i == j) precision else 0.0 } }
            for (b in bins) {
                val z = b.signal.vector()
                if (z[0] == 0.0 && z[1] == 0.0) continue // no information about γ
                val p = 1.0 / (1.0 + exp(-(logit(b.baseline) + g[0] * z[0] + g[1] * z[1])))
                val y = if (b.label) 1.0 else 0.0
                for (i in 0..1) {
                    grad[i] += b.weight * (y - p) * z[i]
                    for (j in 0..1) negH[i][j] += b.weight * p * (1 - p) * z[i] * z[j]
                }
            }
            val step = StatMath.solveSpd(negH, grad)
            g[0] += step[0]; g[1] += step[1]
            if (kotlin.math.abs(step[0]) + kotlin.math.abs(step[1]) < 1e-8) return@repeat
        }
        return Coefficients(g[0], g[1], bins.size, bins.count { it.signal != Signal.NONE })
    }

    private fun logit(p: Double): Double {
        val q = p.coerceIn(1e-6, 1 - 1e-6)
        return ln(q / (1 - q))
    }
}
