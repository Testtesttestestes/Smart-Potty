package com.example.ml

import com.example.data.EventTypes
import com.example.data.TrackerEvent
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * Measures how well the child's own sensations track bladder filling, and compares the
 * child with the model on the same task: "will there be a void within the prediction horizon (5 min)?".
 */
object InteroceptionMetrics {

    val HORIZON_MS = (SignalModel.HORIZON_MIN * 60_000).toLong()
    // Fill at a sensation is interpolated between the surrounding voids, so any daytime gap works.
    private const val PAIR_WINDOW_MS = 3 * 60 * 60_000L
    private const val MIN_SAMPLES = 10
    private const val BOOTSTRAP_ROUNDS = 500

    val LEVELS = listOf("ничего", "немного", "заметно", "сильно")
    fun levelIndex(level: String?): Int? = LEVELS.indexOf(level).takeIf { it >= 0 }

    data class LevelStats(
        val level: String,
        val n: Int,
        val medianFillPct: Double?,
        val q25FillPct: Double?,
        val q75FillPct: Double?,
        val medianVolumeMl: Double?
    )

    enum class Phase { INSUFFICIENT_DATA, A_MODEL_BETTER, B_SIMILAR, C_CHILD_BETTER }

    data class Comparison(
        val n: Int,
        val aucChild: Double?,
        val aucModel: Double?,
        /** 95% bootstrap CI of (AUC child − AUC model). */
        val diffCi: Pair<Double, Double>?,
        val phase: Phase
    )

    data class MonthPoint(val year: Int, val month: Int, val n: Int, val aucChild: Double?, val aucModel: Double?)

    /**
     * Sensitivity of sensation to fill: proportional-odds slope (shared across the
     * "≥ немного / ≥ заметно / ≥ сильно" boundaries). The discrimination threshold is the change in
     * fill (percentage points) that moves the odds of reporting a higher level from 1:3 to 3:1.
     * A shrinking threshold over time = finer interoception.
     */
    data class Sensitivity(val slopePerFill: Double, val thresholdPct: Double?, val n: Int)

    data class MonthSensitivity(val year: Int, val month: Int, val sensitivity: Sensitivity)

    data class Result(
        val sensitivity: Sensitivity?,
        val monthlySensitivity: List<MonthSensitivity>,
        val levels: List<LevelStats>,
        /** Spearman ρ between sensation level and estimated fill at that moment. */
        val sensationFillCorrelation: Double?,
        val pairCount: Int,
        val comparison: Comparison,
        val monthly: List<MonthPoint>
    )

    private data class Pair3(val level: Int, val fill: Double, val volume: Double, val ts: Long)

    private const val MIN_SENSITIVITY_PAIRS = 8

    /** MAP fit of logit P(level ≥ k) = a_k + b·fill (k = 1..3), Gaussian priors N(0, 10²). */
    fun fitSensitivity(levels: List<Int>, fills: List<Double>): Sensitivity? {
        if (levels.size < MIN_SENSITIVITY_PAIRS || levels.distinct().size < 2) return null
        val k = 3
        val theta = DoubleArray(k + 1) // a1..a3, b
        val precision = 1.0 / 100.0
        repeat(50) {
            val grad = DoubleArray(k + 1) { -theta[it] * precision }
            val negH = Array(k + 1) { i -> DoubleArray(k + 1) { j -> if (i == j) precision else 0.0 } }
            for (i in levels.indices) for (split in 1..k) {
                val x = DoubleArray(k + 1).also { it[split - 1] = 1.0; it[k] = fills[i] }
                var eta = 0.0
                for (j in x.indices) eta += theta[j] * x[j]
                val p = 1.0 / (1.0 + kotlin.math.exp(-eta))
                val y = if (levels[i] >= split) 1.0 else 0.0
                for (a in x.indices) {
                    grad[a] += (y - p) * x[a]
                    for (b in x.indices) negH[a][b] += p * (1 - p) * x[a] * x[b]
                }
            }
            val step = StatMath.solveSpd(negH, grad)
            var norm = 0.0
            for (j in theta.indices) { theta[j] += step[j]; norm += kotlin.math.abs(step[j]) }
            if (norm < 1e-9) return@repeat
        }
        val b = theta[k]
        val threshold = if (b > 1e-6) 100.0 * 2.0 * kotlin.math.ln(3.0) / b else null
        return Sensitivity(b, threshold, levels.size)
    }

    private data class Prediction(val ts: Long, val level: Int, val model: Double?, val label: Boolean)

    fun compute(
        events: List<TrackerEvent>,
        extraction: IntervalExtractor.Result,
        capacityMl: Double,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result {
        val voids = extraction.voids
        val sensations = events.filter { it.eventType == EventTypes.SENSATION && levelIndex(it.sensationLevel) != null }
            .sortedBy { it.timestamp }

        // 1. Sensation ↔ fill pairs.
        val pairs = mutableListOf<Pair3>()
        for (s in sensations) {
            val next = voids.firstOrNull { it.timestamp > s.timestamp } ?: continue
            if (next.timestamp - s.timestamp > PAIR_WINDOW_MS || next.volumeMl == null) continue
            val prev = voids.lastOrNull { it.timestamp <= s.timestamp } ?: continue
            if (next.isFirstAfterSleep) continue
            val frac = (s.timestamp - prev.timestamp).toDouble() / (next.timestamp - prev.timestamp)
            pairs.add(Pair3(levelIndex(s.sensationLevel)!!, next.volumeMl * frac / capacityMl, next.volumeMl, s.timestamp))
        }
        // Sensation reported at the moment of voiding: fill = voided volume / capacity.
        for (e in events) {
            if (e.eventType != EventTypes.PEE) continue
            val lvl = levelIndex(e.sensationLevel) ?: continue
            val vol = (e.voidedVolumeMl ?: e.volumeLevel)?.toDouble() ?: continue
            pairs.add(Pair3(lvl, vol / capacityMl, vol, e.timestamp))
        }

        val levels = LEVELS.mapIndexed { i, name ->
            val p = pairs.filter { it.level == i }
            val fills = p.map { it.fill * 100.0 }
            LevelStats(
                level = name,
                n = p.size,
                medianFillPct = StatMath.quantile(fills, 0.5),
                q25FillPct = StatMath.quantile(fills, 0.25),
                q75FillPct = StatMath.quantile(fills, 0.75),
                medianVolumeMl = StatMath.quantile(p.map { it.volume }, 0.5)
            )
        }
        val rho = StatMath.spearman(pairs.map { it.level.toDouble() }, pairs.map { it.fill })

        // 2. Child vs model on "void within the horizon" at each sensation report.
        val predictions = sensations.mapNotNull { s ->
            // Skip reports whose outcome window is not over yet.
            if (now - s.timestamp < HORIZON_MS && voids.none { it.timestamp > s.timestamp }) return@mapNotNull null
            val label = voids.any { it.timestamp > s.timestamp && it.timestamp - s.timestamp <= HORIZON_MS }
            val model = s.predictedProbability?.toDouble()
                ?.takeIf { s.modelVersion == BayesianSurvivalModel.MODEL_VERSION }
            Prediction(s.timestamp, levelIndex(s.sensationLevel)!!, model, label)
        }

        val comparison = compare(predictions)
        val monthly = predictions.groupBy {
            val c = Calendar.getInstance(timeZone).apply { timeInMillis = it.ts }
            c.get(Calendar.YEAR) to c.get(Calendar.MONTH) + 1
        }.toSortedMap(compareBy({ it.first }, { it.second })).map { (ym, list) ->
            val paired = list.filter { it.model != null }
            MonthPoint(
                ym.first, ym.second, list.size,
                StatMath.auc(list.map { it.level.toDouble() }, list.map { it.label }),
                if (paired.size >= 3) StatMath.auc(paired.map { it.model!! }, paired.map { it.label }) else null
            )
        }

        val sensitivity = fitSensitivity(pairs.map { it.level }, pairs.map { it.fill })
        val monthlySensitivity = pairs.groupBy {
            val c = Calendar.getInstance(timeZone).apply { timeInMillis = it.ts }
            c.get(Calendar.YEAR) to c.get(Calendar.MONTH) + 1
        }.toSortedMap(compareBy({ it.first }, { it.second })).mapNotNull { (ym, list) ->
            fitSensitivity(list.map { it.level }, list.map { it.fill })?.let { MonthSensitivity(ym.first, ym.second, it) }
        }
        return Result(sensitivity, monthlySensitivity, levels, rho, pairs.size, comparison, monthly)
    }

    private fun compare(all: List<Prediction>): Comparison {
        val aucChildAll = StatMath.auc(all.map { it.level.toDouble() }, all.map { it.label })
        val paired = all.filter { it.model != null }
        val pos = paired.count { it.label }
        val neg = paired.size - pos
        if (paired.size < MIN_SAMPLES || pos < 3 || neg < 3) {
            return Comparison(all.size, aucChildAll, null, null, Phase.INSUFFICIENT_DATA)
        }
        fun aucPair(list: List<Prediction>): Pair<Double, Double>? {
            val c = StatMath.auc(list.map { it.level.toDouble() }, list.map { it.label }) ?: return null
            val m = StatMath.auc(list.map { it.model!! }, list.map { it.label }) ?: return null
            return c to m
        }
        val (aucChild, aucModel) = aucPair(paired)!!
        val rnd = Random(42)
        val diffs = mutableListOf<Double>()
        repeat(BOOTSTRAP_ROUNDS) {
            val sample = List(paired.size) { paired[rnd.nextInt(paired.size)] }
            aucPair(sample)?.let { diffs.add(it.first - it.second) }
        }
        val lo = StatMath.quantile(diffs, 0.025) ?: 0.0
        val hi = StatMath.quantile(diffs, 0.975) ?: 0.0
        val phase = when {
            hi < 0.0 -> Phase.A_MODEL_BETTER
            lo > 0.0 -> Phase.C_CHILD_BETTER
            else -> Phase.B_SIMILAR
        }
        return Comparison(paired.size, aucChild, aucModel, lo to hi, phase)
    }
}
