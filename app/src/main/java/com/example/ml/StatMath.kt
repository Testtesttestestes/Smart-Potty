package com.example.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Numerical helpers used by the analytics models. Pure Kotlin, no Android dependencies,
 * so everything here is unit-testable on the JVM.
 */
object StatMath {

    private val LANCZOS = doubleArrayOf(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
    )

    /** ln Γ(x) for x > 0 (Lanczos, g = 7). Relative error ~1e-15. */
    fun lnGamma(x: Double): Double {
        require(x > 0.0) { "lnGamma requires x > 0, got $x" }
        if (x < 0.5) {
            // Reflection: Γ(x)Γ(1−x) = π / sin(πx)
            return ln(PI / kotlin.math.sin(PI * x)) - lnGamma(1.0 - x)
        }
        val z = x - 1.0
        var a = LANCZOS[0]
        val t = z + 7.5
        for (i in 1 until LANCZOS.size) a += LANCZOS[i] / (z + i)
        return 0.5 * ln(2.0 * PI) + (z + 0.5) * ln(t) - t + ln(a)
    }

    /** Regularized incomplete beta I_x(a, b), via Lentz continued fraction. */
    fun regularizedIncompleteBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val lnFront = lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1.0 - x)
        // Use the symmetry relation for faster convergence.
        return if (x < (a + 1.0) / (a + b + 2.0)) {
            exp(lnFront) * betaContinuedFraction(x, a, b) / a
        } else {
            1.0 - exp(lnFront) * betaContinuedFraction(1.0 - x, b, a) / b
        }
    }

    private fun betaContinuedFraction(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-300
        val eps = 1e-15
        val qab = a + b
        val qap = a + 1.0
        val qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d
        for (m in 1..300) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < eps) break
        }
        return h
    }

    /** CDF of the standard Student-t distribution with [nu] degrees of freedom. */
    fun studentTCdf(t: Double, nu: Double): Double {
        require(nu > 0.0)
        if (t.isInfinite()) return if (t > 0) 1.0 else 0.0
        val x = nu / (nu + t * t)
        val tail = 0.5 * regularizedIncompleteBeta(x, nu / 2.0, 0.5)
        return if (t >= 0) 1.0 - tail else tail
    }

    /** Quantile of the standard Student-t distribution (bisection on the CDF). */
    fun studentTQuantile(p: Double, nu: Double): Double {
        require(p > 0.0 && p < 1.0)
        var lo = -1e3
        var hi = 1e3
        repeat(200) {
            val mid = 0.5 * (lo + hi)
            if (studentTCdf(mid, nu) < p) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    /** erf(x) — W. J. Cody style rational approximation via erfc, |error| < 1.2e-7. */
    fun erf(x: Double): Double {
        val z = abs(x)
        val t = 1.0 / (1.0 + 0.5 * z)
        val r = t * exp(
            -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
                t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 +
                t * (-0.82215223 + t * 0.17087277))))))))
        )
        return if (x >= 0) 1.0 - r else r - 1.0
    }

    fun normalCdf(x: Double): Double = 0.5 * (1.0 + erf(x / sqrt(2.0)))

    /**
     * Area under the ROC curve (Mann–Whitney U / (n1·n0)) with ties counted as ½.
     * Returns null when one of the classes is empty.
     */
    fun auc(scores: List<Double>, labels: List<Boolean>): Double? {
        require(scores.size == labels.size)
        val pos = scores.filterIndexed { i, _ -> labels[i] }
        val neg = scores.filterIndexed { i, _ -> !labels[i] }
        if (pos.isEmpty() || neg.isEmpty()) return null
        var u = 0.0
        for (p in pos) for (n in neg) {
            u += when {
                p > n -> 1.0
                p == n -> 0.5
                else -> 0.0
            }
        }
        return u / (pos.size.toDouble() * neg.size)
    }

    /** Average ranks (1-based) with ties sharing the mean rank. */
    fun ranks(values: List<Double>): DoubleArray {
        val order = values.indices.sortedBy { values[it] }
        val result = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val avg = (i + j) / 2.0 + 1.0
            for (k in i..j) result[order[k]] = avg
            i = j + 1
        }
        return result
    }

    fun pearson(x: DoubleArray, y: DoubleArray): Double? {
        require(x.size == y.size)
        if (x.size < 3) return null
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in x.indices) {
            sxy += (x[i] - mx) * (y[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
            syy += (y[i] - my) * (y[i] - my)
        }
        if (sxx == 0.0 || syy == 0.0) return null
        return sxy / sqrt(sxx * syy)
    }

    /** Spearman rank correlation; null for fewer than 3 points or a constant variable. */
    fun spearman(x: List<Double>, y: List<Double>): Double? = pearson(ranks(x), ranks(y))

    /** Weighted quantile (linear interpolation over cumulative weight midpoints). */
    fun weightedQuantile(values: List<Double>, weights: List<Double>, q: Double): Double? {
        require(values.size == weights.size)
        val pairs = values.zip(weights).filter { it.second > 0.0 }.sortedBy { it.first }
        if (pairs.isEmpty()) return null
        if (pairs.size == 1) return pairs[0].first
        val total = pairs.sumOf { it.second }
        var cum = 0.0
        val positions = pairs.map { val mid = (cum + it.second / 2.0) / total; cum += it.second; mid }
        if (q <= positions.first()) return pairs.first().first
        if (q >= positions.last()) return pairs.last().first
        for (i in 1 until positions.size) {
            if (q <= positions[i]) {
                val f = (q - positions[i - 1]) / (positions[i] - positions[i - 1])
                return pairs[i - 1].first + f * (pairs[i].first - pairs[i - 1].first)
            }
        }
        return pairs.last().first
    }

    fun quantile(values: List<Double>, q: Double): Double? =
        weightedQuantile(values, List(values.size) { 1.0 }, q)

    /** Solves A·x = b for a small dense symmetric positive-definite A (Cholesky). */
    fun solveSpd(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val l = cholesky(a)
        val n = b.size
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= l[i][k] * y[k]
            y[i] = s / l[i][i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= l[k][i] * x[k]
            x[i] = s / l[i][i]
        }
        return x
    }

    /** Inverse of a small symmetric positive-definite matrix. */
    fun invertSpd(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val cols = Array(n) { j -> solveSpd(a, DoubleArray(n) { if (it == j) 1.0 else 0.0 }) }
        return Array(n) { i -> DoubleArray(n) { j -> cols[j][i] } }
    }

    private fun cholesky(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                if (i == j) {
                    require(s > 0.0) { "Matrix is not positive definite" }
                    l[i][i] = sqrt(s)
                } else {
                    l[i][j] = s / l[j][j]
                }
            }
        }
        return l
    }
}
