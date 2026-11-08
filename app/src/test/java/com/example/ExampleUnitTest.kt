package com.example

import org.junit.Assert.*
import org.junit.Test
import com.example.ml.BayesianSurvivalModel
import com.example.data.TrackerEvent
import java.util.Calendar

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testBayesianSurvivalModel_behavior() {
    val none = BayesianSurvivalModel.Features()
    // 1. Initial state (no past intervals): age prior, low probability right after a void
    val noDataResult = BayesianSurvivalModel.analyze(emptyList(), 0L, none, null)
    assertTrue("Initial prob at 0 time should be < 10%", noDataResult.probNext30 < 0.1f)
    assertTrue("Expected interval should be > 60 mins", (noDataResult.medianIntervalMillis / 60000) > 60)

    // 2. Regular ~120 min intervals
    val intervals = listOf(120.0, 115.0, 125.0, 110.0).map { BayesianSurvivalModel.Observation(it, none) }
    val earlyResult = BayesianSurvivalModel.analyze(intervals, 10L * 60000, none, null)
    assertTrue("Prob at 10 mins should be extremely small", earlyResult.probNext30 < 0.05f)

    val timeToPeeResult = BayesianSurvivalModel.analyze(intervals, 110L * 60000, none, null)
    assertTrue("Prob within 30 min at 110 mins should be substantial", timeToPeeResult.probNext30 > 0.3f)

    val lateResult = BayesianSurvivalModel.analyze(intervals, 240L * 60000, none, null)
    assertTrue("Cumulative prob at 240 mins should be very high (> 80%)", lateResult.cumulativeProbability > 0.8f)

    val distDiff = Math.abs((timeToPeeResult.medianIntervalMillis / 60000) - 120L)
    assertTrue("Expected should adapt to ~120. Diff: $distDiff mins", distDiff < 15L)
  }

  @Test
  fun testTrackerEvent_strictCommandProperties() {
    val event = TrackerEvent(
        childId = "test_child",
        timestamp = System.currentTimeMillis(),
        eventType = "pee",
        isAccident = false,
        isStrictCommand = true,
        commandProbability = 0.74f,
        commandResult = "success"
    )

    assertEquals("test_child", event.childId)
    assertTrue(event.isStrictCommand)
    assertEquals(0.74f, event.commandProbability ?: 0f, 0.01f)
    assertEquals("success", event.commandResult)
    assertFalse(event.isAccident)
  }
}
