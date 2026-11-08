package com.example.ml

/**
 * Bladder capacity and filling estimates.
 *
 * Expected bladder capacity (EBC) by age: Holmdahl (< 2 years) and Koff (≥ 2 years).
 * Personal capacity: maximal voided volume over the last 30 days (excluding the first void
 * after sleep and accidents, as in ICCS bladder-diary practice), taken as a robust weighted
 * 90th percentile and shrunk towards EBC while data is scarce.
 */
object BladderModel {

    /** Density of urine (g/ml): converts the potty scale reading to millilitres. */
    const val URINE_DENSITY_G_PER_ML = 1.015
    private const val WINDOW_MS = 30L * 24 * 60 * 60_000L
    /** Pseudo-observation weight of the age formula in the shrinkage. */
    private const val PRIOR_STRENGTH = 3.0

    fun gramsToMl(grams: Int): Int = Math.round(grams / URINE_DENSITY_G_PER_ML).toInt()

    /** Age-based expected bladder capacity, ml. */
    fun expectedCapacityMl(ageMonths: Double?): Double {
        val months = ageMonths ?: 30.0
        return if (months < 24.0) 38.0 + 2.5 * months else 30.0 * (months / 12.0 + 2.0)
    }

    data class Estimate(
        val expectedCapacityMl: Double,
        val personalCapacityMl: Double,
        /** Number of voids that informed the personal estimate. */
        val sampleSize: Int,
        /** Typical fill rate, ml/min (null without measured volumes). */
        val fillRateMlPerMin: Double?,
        /** Estimated current fill as a fraction of capacity (may exceed 1). */
        val currentFill: Double?
    )

    fun estimate(
        extraction: IntervalExtractor.Result,
        ageMonths: Double?,
        now: Long,
        fallbackIntervalMin: Double
    ): Estimate {
        val ebc = expectedCapacityMl(ageMonths)
        val eligible = extraction.voids.filter {
            it.volumeMl != null && it.volumeMl > 0 && !it.isAccident && !it.isFirstAfterSleep &&
                now - it.timestamp <= WINDOW_MS
        }
        val n = eligible.size
        val p90 = StatMath.weightedQuantile(
            eligible.map { it.volumeMl!! },
            eligible.map { IntervalExtractor.recencyWeight(now - it.timestamp) },
            0.9
        )
        val capacity = if (p90 == null) ebc else (PRIOR_STRENGTH * ebc + n * p90) / (PRIOR_STRENGTH + n)

        val rated = extraction.dayIntervals.filter { it.endVolumeMl != null && it.endVolumeMl > 0 && it.durationMin >= 5 }
        val rate = StatMath.weightedQuantile(
            rated.map { it.endVolumeMl!! / it.durationMin },
            rated.map { it.weight },
            0.5
        ) ?: if (fallbackIntervalMin > 0) capacity * 0.75 / fallbackIntervalMin else null // no volumes yet: assume ~75% fill at a typical void

        val last = extraction.lastVoid
        val fill = if (last != null && rate != null) rate * ((now - last.timestamp) / 60_000.0) / capacity else null
        return Estimate(ebc, capacity, n, rate, fill)
    }
}
