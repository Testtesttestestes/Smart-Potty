package com.example.ml

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Bayesian Model + Survival Analysis + Personalized Intervals
 * 
 * Uses a Bayesian Log-Normal model with a Normal-Inverse-Gamma conjugate prior
 * to model the time between urination events.
 */
object BayesianSurvivalModel {

    data class ModelResult(
        val expectedIntervalMillis: Long,
        val probabilityNow: Float,
        val confidence: Float
    )

    // Standard Normal CDF approximation (Abramowitz & Stegun)
    private fun normalCDF(x: Double): Double {
        val sign = if (x < 0) -1 else 1
        val t = 1.0 / (1.0 + 0.2316419 * Math.abs(x))
        val y = 1.0 - 1.0 / sqrt(2.0 * PI) * exp(-x * x / 2.0) *
            (0.319381530 * t - 0.356563782 * t * t + 1.781477937 * t * t * t - 1.821255978 * t * t * t * t + 1.330274429 * t * t * t * t * t)
        return if (sign == 1) y else 1.0 - y
    }

    /**
     * Calculates the personalized expected interval and the cumulative probability
     * of an event happening by [timeSinceLastEventMillis], given a list of historical intervals.
     */
    fun analyze(pastIntervalsMillis: List<Long>, timeSinceLastEventMillis: Long): ModelResult {
        // Prior parameters (Normal-Inverse-Gamma for Log-Normal distribution)
        // Assume prior mean interval is ~90 minutes (1.5 hours)
        val priorMeanMinutes = 90.0
        val priorMeanLog = ln(priorMeanMinutes) 
        
        val nu0 = 2.0 // weight of the prior mean (equivalent to 2 observations)
        val alpha0 = 3.0 // shape parameter for variance
        val beta0 = 1.0 // scale parameter for variance - controls prior spread
        
        // Ensure intervals are strictly positive to avoid domain errors (log(0))
        val logIntervals = pastIntervalsMillis
            .filter { it > 60_000L } // Minimum 1 minute interval to be valid
            .map { ln(it.toDouble() / 60_000.0) } // Work in minutes

        val n = logIntervals.size
        val yBar = if (n > 0) logIntervals.average() else priorMeanLog
        
        // Update Nu and Mu
        val nuN = nu0 + n
        val muN = (nu0 * priorMeanLog + n * yBar) / nuN
        
        // Update Alpha and Beta
        val alphaN = alpha0 + n / 2.0
        
        var sumSqDiff = 0.0
        if (n > 0) {
            sumSqDiff = logIntervals.sumOf { (it - yBar) * (it - yBar) }
        }
        
        val betaN = beta0 + 0.5 * sumSqDiff + (n * nu0) / (nu0 + n) * 0.5 * (yBar - priorMeanLog) * (yBar - priorMeanLog)

        // The predictive distribution for the next log-interval is roughly Normal with:
        val posteriorVarLog = (betaN / (alphaN - 1.0)) * ((nuN + 1.0) / nuN)
        val posteriorStdLog = sqrt(posteriorVarLog)
        
        // Expected value of Log-Normal(mu, sigma^2) is exp(mu + sigma^2 / 2)
        val expectedMinutes = exp(muN + posteriorVarLog / 2.0)
        val expectedMillis = (expectedMinutes * 60_000.0).toLong()

        // Probability of an event by timeSinceLastEventMillis (CDF of Log-Normal)
        // Ensure minimum 1 minute
        val elapsedMinutes = Math.max(1.0, timeSinceLastEventMillis.toDouble() / 60_000.0)
        val z = (ln(elapsedMinutes) - muN) / posteriorStdLog
        val probability = normalCDF(z).toFloat()

        // Calculate confidence based on data points and variance
        val countWeight = if (n == 0) 0.0 else (n.toDouble() / (n + 5.0))
        val varWeight = kotlin.math.max(0.0, 1.0 - posteriorStdLog / 2.0)
        val confidence = if (n <= 1) 0.1f else (countWeight * varWeight).toFloat().coerceIn(0f, 1f)

        return ModelResult(
            expectedIntervalMillis = expectedMillis,
            probabilityNow = probability.coerceIn(0f, 1f),
            confidence = confidence
        )
    }
}
