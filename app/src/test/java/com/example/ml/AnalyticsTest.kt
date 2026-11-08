package com.example.ml

import com.example.data.Child
import com.example.data.EventTypes
import com.example.data.TrackerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.math.abs
import kotlin.random.Random

class StatMathTest {
    @Test
    fun studentT_matchesReferenceValues() {
        assertEquals(0.5, StatMath.studentTCdf(0.0, 3.0), 1e-12)
        assertEquals(0.94903026, StatMath.studentTCdf(2.0, 5.0), 1e-6)
        assertEquals(0.75, StatMath.studentTCdf(1.0, 1.0), 1e-9) // Cauchy
        assertEquals(1.0 - 0.94903026, StatMath.studentTCdf(-2.0, 5.0), 1e-6)
        // Large ν converges to the normal distribution.
        assertEquals(StatMath.normalCdf(1.3), StatMath.studentTCdf(1.3, 1e6), 1e-5)
        assertEquals(2.015048, StatMath.studentTQuantile(0.95, 5.0), 1e-4)
    }

    @Test
    fun incompleteBeta_andGamma() {
        assertEquals(0.5, StatMath.regularizedIncompleteBeta(0.5, 2.0, 2.0), 1e-12)
        // I_x(1, b) = 1 − (1 − x)^b
        assertEquals(1 - Math.pow(0.7, 3.0), StatMath.regularizedIncompleteBeta(0.3, 1.0, 3.0), 1e-10)
        assertEquals(Math.log(24.0), StatMath.lnGamma(5.0), 1e-10)
        assertEquals(0.5 * Math.log(Math.PI), StatMath.lnGamma(0.5), 1e-10)
    }

    @Test
    fun normalCdf_accurate() {
        assertEquals(0.975002, StatMath.normalCdf(1.96), 1e-6)
        assertEquals(0.5, StatMath.normalCdf(0.0), 1e-7)
    }

    @Test
    fun auc_spearman_quantiles() {
        assertEquals(1.0, StatMath.auc(listOf(0.1, 0.2, 0.8, 0.9), listOf(false, false, true, true))!!, 1e-12)
        assertEquals(0.5, StatMath.auc(listOf(1.0, 1.0), listOf(false, true))!!, 1e-12)
        assertNull(StatMath.auc(listOf(1.0), listOf(true)))
        assertEquals(1.0, StatMath.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(10.0, 20.0, 25.0, 100.0))!!, 1e-12)
        assertEquals(2.5, StatMath.quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.5)!!, 1e-12)
    }

    @Test
    fun solveSpd() {
        val a = arrayOf(doubleArrayOf(4.0, 1.0), doubleArrayOf(1.0, 3.0))
        val x = StatMath.solveSpd(a, doubleArrayOf(1.0, 2.0))
        assertEquals(1.0 / 11, x[0], 1e-12)
        assertEquals(7.0 / 11, x[1], 1e-12)
    }
}

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

private fun pee(ts: Long, ml: Int? = 120, accident: Boolean = false) =
    TrackerEvent(childId = "c", timestamp = ts, eventType = EventTypes.PEE, voidedVolumeMl = ml, isAccident = accident)

private fun event(ts: Long, type: String, sensation: String? = null, drink: Int? = null, p: Float? = null) =
    TrackerEvent(
        childId = "c", timestamp = ts, eventType = type, sensationLevel = sensation, fluidIntakeMl = drink,
        predictedProbability = p, modelVersion = p?.let { BayesianSurvivalModel.MODEL_VERSION }
    )

class IntervalExtractorTest {
    @Test
    fun mergesSplitSessions() {
        val r = IntervalExtractor.extract(
            listOf(pee(0, 80), pee(3 * MIN, 40), pee(120 * MIN, 100)), null, 200 * MIN, UTC
        )
        assertEquals(2, r.voids.size)
        assertEquals(120.0, r.voids[0].volumeMl!!, 1e-9)
        assertEquals(1, r.intervals.size)
        assertEquals(120.0, r.intervals[0].durationMin, 1e-9)
    }

    @Test
    fun nightFromEventsScheduleAndThreshold() {
        // Explicit sleep/wake events.
        val explicit = IntervalExtractor.extract(
            listOf(pee(0), event(HOUR, EventTypes.SLEEP), event(9 * HOUR, EventTypes.WAKE), pee(9 * HOUR + 5 * MIN), pee(11 * HOUR)),
            null, 12 * HOUR, UTC
        )
        assertTrue(explicit.intervals[0].isNight)
        assertTrue(explicit.voids[1].isFirstAfterSleep)
        assertTrue(explicit.intervals[1].startsAfterWake)
        assertTrue(!explicit.intervals[1].isNight)

        // Profile schedule 21:00-07:00 (UTC midnight = 0).
        val scheduled = IntervalExtractor.extract(
            listOf(pee(20 * HOUR), pee(DAY + 7 * HOUR + 10 * MIN), pee(DAY + 9 * HOUR)),
            "21:00-07:00", DAY + 10 * HOUR, UTC
        )
        assertTrue(scheduled.intervals[0].isNight)
        assertTrue(!scheduled.intervals[1].isNight)

        // No sleep info: > 6 h is night.
        val threshold = IntervalExtractor.extract(listOf(pee(0), pee(7 * HOUR), pee(9 * HOUR)), null, 10 * HOUR, UTC)
        assertTrue(threshold.intervals[0].isNight)
        assertTrue(!threshold.intervals[1].isNight)
    }

    @Test
    fun asleepNowAndIntake() {
        val r = IntervalExtractor.extract(
            listOf(pee(0), event(10 * MIN, EventTypes.DRINK, drink = 150), event(30 * MIN, EventTypes.SLEEP)),
            null, HOUR, UTC
        )
        assertTrue(r.isAsleepNow)
        assertEquals(150.0, r.intakeSinceLastVoidMl, 1e-9)
    }

    @Test
    fun recencyWeightHalvesEveryHalfLife() {
        assertEquals(1.0, IntervalExtractor.recencyWeight(0), 1e-12)
        assertEquals(0.5, IntervalExtractor.recencyWeight((IntervalExtractor.RECENCY_HALF_LIFE_DAYS * DAY).toLong()), 1e-9)
    }

    @Test
    fun parseSchedule() {
        assertEquals(21 * 60 to 7 * 60 + 30, IntervalExtractor.parseSchedule("сон 21:00 – 07:30"))
        assertNull(IntervalExtractor.parseSchedule("рано"))
    }
}

class BayesianSurvivalModelTest {
    private fun obs(vararg minutes: Double) = minutes.map {
        BayesianSurvivalModel.Observation(it, BayesianSurvivalModel.Features())
    }

    @Test
    fun noData_usesAgePrior() {
        val young = BayesianSurvivalModel.analyze(emptyList(), 0L, BayesianSurvivalModel.Features(), 12.0)
        val older = BayesianSurvivalModel.analyze(emptyList(), 0L, BayesianSurvivalModel.Features(), 48.0)
        assertEquals(60.0, young.medianIntervalMillis / 60_000.0, 0.5)
        assertEquals(120.0, older.medianIntervalMillis / 60_000.0, 0.5)
        assertEquals(0f, young.confidence, 1e-6f)
        assertTrue(young.probNext15 < 0.1f)
    }

    @Test
    fun learnsRegularIntervals_andConditionsOnSurvival() {
        val rnd = Random(1)
        val data = obs(*DoubleArray(30) { 120.0 + rnd.nextDouble(-10.0, 10.0) })
        val at10 = BayesianSurvivalModel.analyze(data, 10 * MIN, BayesianSurvivalModel.Features(), 30.0)
        val at100 = BayesianSurvivalModel.analyze(data, 100 * MIN, BayesianSurvivalModel.Features(), 30.0)
        val at200 = BayesianSurvivalModel.analyze(data, 200 * MIN, BayesianSurvivalModel.Features(), 30.0)

        assertEquals(120.0, at10.medianIntervalMillis / 60_000.0, 8.0)
        assertTrue("early prob ${at10.probNext30}", at10.probNext30 < 0.01f)
        assertTrue("prob near median ${at100.probNext30}", at100.probNext30 > 0.5f)
        // Conditional: past the usual interval a void is very likely soon, while the
        // unconditional CDF alone would only say "it should have happened already".
        assertTrue(at200.probNext30 > 0.6f)
        assertTrue(at10.confidence > 0.6f)
        val (lo, hi) = at10.interval80Millis
        assertTrue(lo / 60_000.0 > 95 && hi / 60_000.0 < 145)
    }

    @Test
    fun smallSample_hasWiderStudentTInterval() {
        val few = BayesianSurvivalModel.analyze(obs(120.0, 125.0), 0L, BayesianSurvivalModel.Features(), 30.0)
        val many = BayesianSurvivalModel.analyze(obs(*DoubleArray(40) { if (it % 2 == 0) 120.0 else 125.0 }), 0L, BayesianSurvivalModel.Features(), 30.0)
        val wFew = few.interval80Millis.second - few.interval80Millis.first
        val wMany = many.interval80Millis.second - many.interval80Millis.first
        assertTrue(wFew > 2 * wMany)
        assertTrue(few.confidence < many.confidence)
    }

    @Test
    fun learnsFluidIntakeEffect() {
        val rnd = Random(7)
        val data = List(60) {
            val intake = if (it % 2 == 0) 0.0 else 250.0
            val minutes = if (intake > 0) 70.0 else 140.0
            BayesianSurvivalModel.Observation(minutes * Math.exp(rnd.nextDouble(-0.05, 0.05)), BayesianSurvivalModel.Features(intake))
        }
        val post = BayesianSurvivalModel.fit(data, 30.0)
        assertTrue("β1 = ${post.mean[1]}", post.mean[1] < -0.3)
        val dry = post.predictive(BayesianSurvivalModel.Features(0.0)).medianMinutes()
        val wet = post.predictive(BayesianSurvivalModel.Features(250.0)).medianMinutes()
        assertEquals(140.0, dry, 8.0)
        assertEquals(70.0, wet, 5.0)
    }

    @Test
    fun recencyWeightsFollowChange() {
        val old = List(30) { BayesianSurvivalModel.Observation(60.0, BayesianSurvivalModel.Features(), 0.05) }
        val recent = List(10) { BayesianSurvivalModel.Observation(150.0, BayesianSurvivalModel.Features(), 1.0) }
        val median = BayesianSurvivalModel.fit(old + recent, 30.0).predictive(BayesianSurvivalModel.Features()).medianMinutes()
        assertTrue("median $median", median > 125.0)
    }
}

class BladderModelTest {
    @Test
    fun ageFormulas() {
        assertEquals(38.0 + 2.5 * 12, BladderModel.expectedCapacityMl(12.0), 1e-9) // Holmdahl
        assertEquals(30.0 * (3 + 2), BladderModel.expectedCapacityMl(36.0), 1e-9) // Koff
        assertEquals(148, BladderModel.gramsToMl(150))
    }

    @Test
    fun personalCapacity_shrinksToAgeAndExcludesMorningVoid() {
        val now = 10 * DAY
        val events = listOf(
            pee(now - 20 * HOUR, 100), event(now - 19 * HOUR, EventTypes.SLEEP), event(now - 8 * HOUR, EventTypes.WAKE),
            pee(now - 7 * HOUR, 400), // first void after sleep: excluded
            pee(now - 5 * HOUR, 200), pee(now - 3 * HOUR, 200), pee(now - HOUR, 200)
        )
        val ex = IntervalExtractor.extract(events, null, now, UTC)
        val est = BladderModel.estimate(ex, 36.0, now, 120.0)
        assertEquals(150.0, est.expectedCapacityMl, 1e-9)
        // n = 4 eligible (100, 200, 200, 200): between EBC and the personal maximum.
        assertTrue(est.personalCapacityMl > 150.0 && est.personalCapacityMl < 200.0)
        assertEquals(4, est.sampleSize)
        // Day intervals: 120 min each, ending with 200 ml → 200/120 ml/min (the night interval is excluded).
        assertEquals(200.0 / 120.0, est.fillRateMlPerMin!!, 1e-9)
        assertEquals(200.0 / 120.0 * 60.0 / est.personalCapacityMl, est.currentFill!!, 1e-9)
    }
}

class InteroceptionMetricsTest {
    /** Child reports: "сильно" shortly before every void, "ничего" right after. */
    private fun dataset(childInformative: Boolean, days: Int = 20): List<TrackerEvent> {
        val rnd = Random(3)
        val events = mutableListOf<TrackerEvent>()
        var t = 0L
        repeat(days * 6) {
            events.add(pee(t, 150))
            val next = t + 120 * MIN
            val lateLevel = if (childInformative) "сильно" else InteroceptionMetrics.LEVELS[rnd.nextInt(4)]
            val earlyLevel = if (childInformative) "ничего" else InteroceptionMetrics.LEVELS[rnd.nextInt(4)]
            // Model is mediocre: ~0.5 everywhere with noise.
            events.add(event(t + 20 * MIN, EventTypes.SENSATION, earlyLevel, p = (0.4 + rnd.nextDouble() * 0.3).toFloat()))
            events.add(event(next - 15 * MIN, EventTypes.SENSATION, lateLevel, p = (0.4 + rnd.nextDouble() * 0.3).toFloat()))
            t = next
        }
        return events
    }

    @Test
    fun informativeChild_isPhaseC() {
        val events = dataset(childInformative = true)
        val now = events.maxOf { it.timestamp } + HOUR
        val ex = IntervalExtractor.extract(events, null, now, UTC)
        val r = InteroceptionMetrics.compute(events, ex, 200.0, now, UTC)
        // The very last report has no void after it (window over → negative), hence not exactly 1.
        assertEquals(1.0, r.comparison.aucChild!!, 0.01)
        assertEquals(InteroceptionMetrics.Phase.C_CHILD_BETTER, r.comparison.phase)
        assertTrue(r.sensationFillCorrelation!! > 0.9)
        val strong = r.levels.first { it.level == "сильно" }
        assertEquals(150.0 * (105.0 / 120.0) / 200.0 * 100.0, strong.medianFillPct!!, 1e-6)
        assertNotNull(r.monthly.firstOrNull())
    }

    @Test
    fun randomChild_isNotPhaseC() {
        val events = dataset(childInformative = false)
        val now = events.maxOf { it.timestamp } + HOUR
        val ex = IntervalExtractor.extract(events, null, now, UTC)
        val r = InteroceptionMetrics.compute(events, ex, 200.0, now, UTC)
        assertTrue(abs(r.comparison.aucChild!! - 0.5) < 0.15)
        assertTrue(r.comparison.phase != InteroceptionMetrics.Phase.C_CHILD_BETTER)
    }

    @Test
    fun tooFewReports_isInsufficient() {
        val events = listOf(pee(0), event(100 * MIN, EventTypes.SENSATION, "сильно", p = 0.5f), pee(110 * MIN))
        val ex = IntervalExtractor.extract(events, null, 5 * HOUR, UTC)
        val r = InteroceptionMetrics.compute(events, ex, 200.0, 5 * HOUR, UTC)
        assertEquals(InteroceptionMetrics.Phase.INSUFFICIENT_DATA, r.comparison.phase)
    }
}

class TrackerAnalyticsTest {
    @Test
    fun endToEnd_regularChild() {
        val now = 30 * DAY + 12 * HOUR
        val events = mutableListOf<TrackerEvent>()
        // 30 days, voids every 2 h from 07:00 to 19:00, sleep 20:00–07:00 in the profile.
        for (d in 0 until 30) for (h in 7..19 step 2) events.add(pee(d * DAY + h * HOUR, 140))
        events.add(pee(30 * DAY + 7 * HOUR, 140))
        events.add(pee(30 * DAY + 9 * HOUR, 140))
        events.add(pee(30 * DAY + 11 * HOUR, 140))
        val child = Child(childId = "c", name = "x", birthDate = "1968-01-01", sleepSchedule = "20:00-07:00")
        val snap = TrackerAnalytics.compute(events, child, now, UTC)

        assertEquals(120.0, snap.model.medianIntervalMillis / 60_000.0, 3.0)
        assertTrue(snap.model.probNext30 < 0.05f) // only 60 min since last void
        assertTrue(snap.calibration.n > 100)
        assertTrue("Brier ${snap.calibration.brier}", snap.calibration.brier!! < 0.1)
        assertTrue(snap.calibration.skill!! > 0.5)
        assertTrue(!snap.isAsleepNow)
        assertEquals(140.0, snap.bladder.personalCapacityMl, 15.0)
        assertTrue(snap.ageMonths!! > 0)
    }

    @Test
    fun noAlertWhileAsleep() {
        val events = listOf(pee(0), pee(2 * HOUR), event(2 * HOUR + 5 * MIN, EventTypes.SLEEP))
        val snap = TrackerAnalytics.compute(events, null, 6 * HOUR, UTC)
        assertTrue(snap.isAsleepNow)
        assertTrue(!snap.shouldAlert)
    }

    @Test
    fun ageParsing() {
        assertNull(TrackerAnalytics.ageMonths("bad", 0L, UTC))
        assertEquals(12.0, TrackerAnalytics.ageMonths("1970-01-01", (365.25 * DAY).toLong(), UTC)!!, 0.01)
    }
}
