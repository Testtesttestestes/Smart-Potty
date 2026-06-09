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
    // 1. Initial state (no past intervals)
    val noDataResult = BayesianSurvivalModel.analyze(emptyList(), 0L)
    
    // Prob should be very small right after pee
    assertTrue("Initial prob at 0 time should be < 10%", noDataResult.probabilityNow < 0.1f)
    assertTrue("Expected interval should be > 60 mins", (noDataResult.expectedIntervalMillis / 60000) > 60)

    // 2. Add some regular ~120 min intervals
    val intervals = listOf(
        120L * 60000,
        115L * 60000,
        125L * 60000,
        110L * 60000
    )
    
    // Check at 10 minutes (should be almost 0% prob)
    val earlyResult = BayesianSurvivalModel.analyze(intervals, 10L * 60000)
    assertTrue("Prob at 10 mins should be extremely small", earlyResult.probabilityNow < 0.05f)
    
    // Check at 120 minutes (should be roughly around 50% depending on variance)
    val timeToPeeResult = BayesianSurvivalModel.analyze(intervals, 120L * 60000)
    assertTrue("Prob at 120 mins should be substantial (> 30%)", timeToPeeResult.probabilityNow > 0.3f)
    
    // Check at 240 minutes (should be nearly 100%)
    val lateResult = BayesianSurvivalModel.analyze(intervals, 240L * 60000)
    assertTrue("Prob at 240 mins should be very high (> 80%)", lateResult.probabilityNow > 0.8f)
    
    // Expected interval should adapt near to 120 mins
    val distDiff = Math.abs((timeToPeeResult.expectedIntervalMillis / 60000) - 120L)
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
