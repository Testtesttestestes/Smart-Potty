package com.example.ml

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Bayesian survival model for the time between voids.
 *
 * log(interval, min) = β0 + β1·ln(1 + intake/100) + β2·[starts after wake]
 *                      + β3·(weeks from now) + β4·ln(1 + held/10) + ε,  ε ~ N(0, σ²)
 *
 * β3 is a local linear trend: the bladder stretches (growth, hold training), so the model
 * extrapolates the trend instead of only lagging behind it; β0 is the level *now*.
 * β4 separates intervals lengthened by a "потерпи" command from the natural rhythm.
 *
 * Conjugate Normal-Inverse-Gamma prior → closed-form weighted posterior. The posterior
 * predictive of the next log-interval is Student-t, so small samples get honest heavy tails.
 * The currently open interval is right-censored: all "now" probabilities are conditional on
 * the child not having voided yet.
 */
object BayesianSurvivalModel {

    /** Bump when the meaning of stored probabilities changes (stored in TrackerEvent.modelVersion). */
    const val MODEL_VERSION = 3 // 3: stored probabilities are P(void in next 5 min) incl. signals

    // Prior on the noise variance σ² ~ InvGamma(A0, B0): E[σ²] = 0.15 (σ ≈ 0.4 → typical spread ≈ ×1.5),
    // worth only 2·A0 = 4 pseudo-observations so the child's own regularity takes over quickly.
    private const val A0 = 2.0
    private const val B0 = 0.15
    /** Prior means of β1 (fluid intake shortens the interval) and β4 (holding lengthens it). */
    private const val PRIOR_INTAKE_EFFECT = -0.2
    private const val PRIOR_HOLD_EFFECT = 0.3
    /** Prior covariance diagonal, in units of σ² (β0 ≙ 2 pseudo-observations; trend ±~10%/week). */
    private val PRIOR_V_DIAG = doubleArrayOf(0.5, 1.0, 0.5, 0.05, 0.5)
    const val WEEK_MS = 7L * 24 * 60 * 60_000L

    data class Features(
        val intakeMl: Double = 0.0,
        val startsAfterWake: Boolean = false,
        /** Time of the interval relative to the reference "now", in weeks (≤ 0 for history). */
        val weeksFromNow: Double = 0.0,
        /** Minutes held after a "потерпи" command within the interval. */
        val heldMin: Double = 0.0
    ) {
        fun vector() = doubleArrayOf(
            1.0, ln(1.0 + intakeMl / 100.0), if (startsAfterWake) 1.0 else 0.0,
            weeksFromNow, ln(1.0 + heldMin / 10.0)
        )
    }

    data class Observation(val durationMin: Double, val features: Features, val weight: Double = 1.0)

    /** Posterior predictive of the log-interval: location + scale·T(ν). */
    data class Predictive(val location: Double, val scale: Double, val dof: Double) {
        fun cdfMinutes(minutes: Double): Double =
            if (minutes <= 0.0) 0.0 else StatMath.studentTCdf((ln(minutes) - location) / scale, dof)

        fun quantileMinutes(p: Double): Double = exp(location + scale * StatMath.studentTQuantile(p, dof))

        fun medianMinutes(): Double = exp(location)

        /** P(void within the next [horizonMin] | no void during the first [elapsedMin]). */
        fun conditionalProbability(elapsedMin: Double, horizonMin: Double): Double {
            val f0 = cdfMinutes(elapsedMin)
            val survival = 1.0 - f0
            if (survival < 1e-9) return 1.0
            return ((cdfMinutes(elapsedMin + horizonMin) - f0) / survival).coerceIn(0.0, 1.0)
        }

        /** Median remaining time given survival to [elapsedMin]. */
        fun medianRemainingMinutes(elapsedMin: Double): Double {
            val f0 = cdfMinutes(elapsedMin)
            val target = (f0 + (1.0 - f0) / 2.0).coerceIn(1e-9, 1.0 - 1e-9)
            return (quantileMinutes(target) - elapsedMin).coerceAtLeast(0.0)
        }

        /** Width of the central 80% predictive interval on the log scale. */
        fun logWidth80(): Double = 2.0 * scale * StatMath.studentTQuantile(0.9, dof)
    }

    class Posterior internal constructor(
        val mean: DoubleArray,
        private val covUnscaled: Array<DoubleArray>,
        val a: Double,
        val b: Double,
        val effectiveN: Double
    ) {
        fun predictive(x: Features): Predictive {
            val v = x.vector()
            var loc = 0.0
            for (i in v.indices) loc += mean[i] * v[i]
            var quad = 0.0
            for (i in v.indices) for (j in v.indices) quad += v[i] * covUnscaled[i][j] * v[j]
            return Predictive(loc, sqrt(b / a * (1.0 + quad)), 2.0 * a)
        }
    }

    /** Age-dependent prior median of the daytime interval, minutes. */
    fun priorMedianMinutes(ageMonths: Double?): Double = when {
        ageMonths == null -> 90.0
        ageMonths < 18 -> 60.0
        ageMonths < 36 -> 90.0
        ageMonths < 60 -> 120.0
        else -> 150.0
    }

    fun fit(observations: List<Observation>, ageMonths: Double?): Posterior {
        val p = PRIOR_V_DIAG.size
        val m0 = doubleArrayOf(ln(priorMedianMinutes(ageMonths)), PRIOR_INTAKE_EFFECT, 0.0, 0.0, PRIOR_HOLD_EFFECT)
        val lambda0 = Array(p) { i -> DoubleArray(p) { j -> if (i == j) 1.0 / PRIOR_V_DIAG[i] else 0.0 } }

        val lambdaN = Array(p) { i -> lambda0[i].copyOf() }
        val rhs = DoubleArray(p) { i -> lambda0[i][i] * m0[i] }
        var yWy = 0.0
        var sumW = 0.0
        for (o in observations) {
            if (o.durationMin < 1.0 || o.weight <= 0.0) continue
            val x = o.features.vector()
            val y = ln(o.durationMin)
            for (i in 0 until p) {
                for (j in 0 until p) lambdaN[i][j] += o.weight * x[i] * x[j]
                rhs[i] += o.weight * x[i] * y
            }
            yWy += o.weight * y * y
            sumW += o.weight
        }
        val mN = StatMath.solveSpd(lambdaN, rhs)
        var m0L0m0 = 0.0
        var mNLNmN = 0.0
        for (i in 0 until p) for (j in 0 until p) {
            m0L0m0 += m0[i] * lambda0[i][j] * m0[j]
            mNLNmN += mN[i] * lambdaN[i][j] * mN[j]
        }
        val aN = A0 + sumW / 2.0
        val bN = (B0 + 0.5 * (yWy + m0L0m0 - mNLNmN)).coerceAtLeast(1e-6)
        return Posterior(mN, StatMath.invertSpd(lambdaN), aN, bN, sumW)
    }

    fun prior(ageMonths: Double?): Posterior = fit(emptyList(), ageMonths)

    data class ModelResult(
        val medianIntervalMillis: Long,
        val interval80Millis: Pair<Long, Long>,
        /** Unconditional F(elapsed) — legacy meaning of the old `probabilityNow`. */
        val cumulativeProbability: Float,
        val probNext5: Float,
        val probNext15: Float,
        val probNext30: Float,
        val probNext60: Float,
        val medianTimeLeftMillis: Long?,
        val confidence: Float,
        val effectiveN: Double,
        val hasLastVoid: Boolean,
        /** Relative change of the typical interval per week (trend), e.g. 0.05 = +5%/week. */
        val intervalTrendPerWeek: Double,
        /** Multiplicative effect of holding 10 minutes after a command on the interval. */
        val holdEffect: Double
    )

    fun analyze(
        observations: List<Observation>,
        elapsedMillis: Long?,
        currentFeatures: Features,
        ageMonths: Double?
    ): ModelResult {
        val post = fit(observations, ageMonths)
        val pred = post.predictive(currentFeatures)
        val priorPred = prior(ageMonths).predictive(currentFeatures)
        val confidence = (1.0 - pred.logWidth80() / priorPred.logWidth80()).coerceIn(0.0, 1.0)

        val elapsedMin = elapsedMillis?.let { it / 60_000.0 }
        fun ms(min: Double) = (min * 60_000.0).toLong()
        return ModelResult(
            medianIntervalMillis = ms(pred.medianMinutes()),
            interval80Millis = ms(pred.quantileMinutes(0.1)) to ms(pred.quantileMinutes(0.9)),
            cumulativeProbability = (elapsedMin?.let { pred.cdfMinutes(it) } ?: 0.0).toFloat(),
            probNext5 = (elapsedMin?.let { pred.conditionalProbability(it, 5.0) } ?: 0.0).toFloat(),
            probNext15 = (elapsedMin?.let { pred.conditionalProbability(it, 15.0) } ?: 0.0).toFloat(),
            probNext30 = (elapsedMin?.let { pred.conditionalProbability(it, 30.0) } ?: 0.0).toFloat(),
            probNext60 = (elapsedMin?.let { pred.conditionalProbability(it, 60.0) } ?: 0.0).toFloat(),
            medianTimeLeftMillis = elapsedMin?.let { ms(pred.medianRemainingMinutes(it)) },
            confidence = confidence.toFloat(),
            effectiveN = post.effectiveN,
            hasLastVoid = elapsedMillis != null,
            intervalTrendPerWeek = exp(post.mean[3]) - 1.0,
            holdEffect = exp(post.mean[4] * ln(2.0))
        )
    }

    fun featuresOf(iv: IntervalExtractor.Interval, referenceTs: Long) = Features(
        intakeMl = iv.intakeMl,
        startsAfterWake = iv.startsAfterWake,
        weeksFromNow = (iv.endTs - referenceTs).toDouble() / WEEK_MS,
        heldMin = iv.heldMin
    )

    fun observationsFrom(intervals: List<IntervalExtractor.Interval>, referenceTs: Long): List<Observation> =
        intervals.filter { !it.isNight }.map { Observation(it.durationMin, featuresOf(it, referenceTs), it.weight) }
}
