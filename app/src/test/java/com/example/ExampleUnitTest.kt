package com.example

import org.junit.Assert.*
import org.junit.Test
import com.example.ml.FeatureStore
import com.example.ml.EventFeatures
import com.example.ml.GradientBoostingModel
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
  fun featureGeneration_isCorrect() {
    val childId = "test-child"
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.HOUR_OF_DAY, 12)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    
    val baseTime = calendar.timeInMillis
    
    val pastEvent = TrackerEvent(
        childId = childId,
        timestamp = baseTime - (115 * 60 * 1000), // 115 minutes ago
        eventType = "pee",
        isAccident = false
    )
    
    val features = FeatureStore.generateFeatures(
        allEvents = listOf(pastEvent),
        targetTimestamp = baseTime,
        sleepState = false,
        fluidIntake = 120
    )
    
    assertEquals(115, features.minutesAfterLastPee)
    assertEquals(12, features.timeOfDay)
    assertEquals(false, features.sleepState)
    assertEquals(120, features.fluidIntake)
  }

  @Test
  fun testGradientBoostingModel_f1Score() {
    // Generate synthetic data
    val data = mutableListOf<Pair<EventFeatures, Boolean>>()
    
    // Positive cases (Urge within 15 mins)
    for (i in 0 until 50) {
        // Long time since pee, high fluid intake -> high chance
        data.add(Pair(EventFeatures((100..150).random(), (10..18).random(), false, (150..300).random()), true))
    }
    
    // Negative cases (No urge)
    for (i in 0 until 50) {
        // Short time since pee, lower fluid intake -> low chance
        data.add(Pair(EventFeatures((0..40).random(), (10..18).random(), false, (0..100).random()), false))
    }

    // Some fuzzy edge cases
    for (i in 0 until 10) {
        data.add(Pair(EventFeatures((80..90).random(), (2..5).random(), true, (0..50).random()), false)) // sleeping, low intake
        data.add(Pair(EventFeatures((50..80).random(), 14, false, 200), true)) // moderate time, high intake
    }

    val model = GradientBoostingModel(numTrees = 20, learningRate = 0.5f)
    model.fit(data)

    val metrics = model.evaluate(data)
    
    println("Precision: ${metrics.precision}, Recall: ${metrics.recall}, F1: ${metrics.f1}")
    assertTrue("F1 score should be > 0.7. Actual: ${metrics.f1}", metrics.f1 > 0.7f)
  }

  @Test
  fun testOnlineUpdate_noDegradation() {
    val model = GradientBoostingModel(numTrees = 20, learningRate = 0.5f)
    val testData = mutableListOf<Pair<EventFeatures, Boolean>>()
    
    // Create base data
    for (i in 0 until 50) {
        model.fitOnline(Pair(EventFeatures((100..150).random(), 12, false, 200), true))
        model.fitOnline(Pair(EventFeatures((0..40).random(), 12, false, 50), false))
    }

    // create holdout test set
    for (i in 0 until 30) {
        testData.add(Pair(EventFeatures((120..150).random(), 12, false, 200), true))
        testData.add(Pair(EventFeatures((0..30).random(), 12, false, 50), false))
    }

    val baseMetrics = model.evaluate(testData)

    // simulate online scenario with random edge cases
    for (i in 0 until 50) {
        val features = EventFeatures((40..90).random(), (10..18).random(), false, (100..150).random())
        val actualOutcome = if (features.minutesAfterLastPee > 80 && features.fluidIntake > 120) true else false
        model.fitOnline(Pair(features, actualOutcome))
    }

    val updatedMetrics = model.evaluate(testData)
    
    println("Base F1: ${baseMetrics.f1}, Updated F1: ${updatedMetrics.f1}")
    assertTrue("Updated F1 should not degrade below 0.7", updatedMetrics.f1 >= 0.7f)
    // We allow a tiny slight fluctuation usually but the f1 should generally stay high
  }
}
