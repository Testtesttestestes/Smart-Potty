package com.example.ml

import com.example.data.EventTypes
import com.example.data.Initiators
import com.example.data.TrackerEvent

/**
 * How the app talks to the child. The goal is interoception, not reminders: by default the app
 * stays silent and only measures; hints are a safety net that fades out as the child succeeds,
 * and hold training follows an adaptive staircase.
 */
object Coaching {

    enum class Mode {
        /** Predict and measure only; never prompt the child. */
        QUIET,
        /** "Пора на горшок" only when an accident is imminent; the bar rises with the child's success. */
        SAFETY,
        /** "Потерпи N минут" with an adaptive staircase of N. */
        HOLD_TRAINING
    }

    enum class Action { NONE, PROMPT_GO, COMMAND_HOLD }

    data class Settings(
        val mode: Mode = Mode.QUIET,
        val stepMin: Int = 1,
        val startHoldMin: Int = 2,
        /** Upper bound of the estimated fill (fraction of capacity) a hold may reach. */
        val maxFillDuringHold: Double = 1.05
    )

    // ---- Adaptive staircase (3 successes up / 1 failure down → converges near 80% success) ----

    enum class HoldOutcome { SUCCESS, FAILED_ACCIDENT, FAILED_EARLY, PENDING }

    data class HoldTrial(val commandTs: Long, val targetMin: Int, val outcome: HoldOutcome)

    data class Staircase(val nextTargetMin: Int, val trials: List<HoldTrial>, val recentSuccessRate: Double?)

    private const val UP_AFTER = 3

    fun holdOutcome(command: TrackerEvent, events: List<TrackerEvent>): HoldOutcome {
        val target = command.holdTargetMin ?: return HoldOutcome.PENDING
        val first = events.filter {
            it.timestamp > command.timestamp &&
                (it.eventType == EventTypes.PEE || it.eventType == EventTypes.LEAK)
        }.minByOrNull { it.timestamp } ?: return HoldOutcome.PENDING
        val heldMin = (first.timestamp - command.timestamp) / 60_000.0
        val accident = first.eventType == EventTypes.LEAK || first.isAccident
        return when {
            accident -> HoldOutcome.FAILED_ACCIDENT
            heldMin < target -> HoldOutcome.FAILED_EARLY
            else -> HoldOutcome.SUCCESS
        }
    }

    fun staircase(events: List<TrackerEvent>, settings: Settings): Staircase {
        val commands = events.filter { it.eventType == EventTypes.COMMAND && it.holdTargetMin != null }
            .sortedBy { it.timestamp }
        var target = settings.startHoldMin
        var streak = 0
        val trials = commands.map { c ->
            val o = holdOutcome(c, events)
            when (o) {
                HoldOutcome.SUCCESS -> {
                    streak++
                    if (streak >= UP_AFTER) { target = c.holdTargetMin!! + settings.stepMin; streak = 0 }
                    else target = c.holdTargetMin!!
                }
                HoldOutcome.FAILED_ACCIDENT, HoldOutcome.FAILED_EARLY -> {
                    target = (c.holdTargetMin!! - settings.stepMin).coerceAtLeast(1); streak = 0
                }
                HoldOutcome.PENDING -> {}
            }
            HoldTrial(c.timestamp, c.holdTargetMin!!, o)
        }
        val done = trials.filter { it.outcome != HoldOutcome.PENDING }.takeLast(10)
        val rate = if (done.isEmpty()) null else done.count { it.outcome == HoldOutcome.SUCCESS }.toDouble() / done.size
        return Staircase(target, trials, rate)
    }

    /** Hold target capped so the estimated fill stays under [Settings.maxFillDuringHold]. */
    fun cappedHoldMin(stairTarget: Int, bladder: BladderModel.Estimate, settings: Settings): Int {
        val rate = bladder.fillRateMlPerMin ?: return stairTarget
        val fill = bladder.currentFill ?: return stairTarget
        val roomMl = (settings.maxFillDuringHold - fill) * bladder.personalCapacityMl
        val capMin = (roomMl / rate).toInt()
        return stairTarget.coerceAtMost(capMin).coerceAtLeast(1)
    }

    // ---- Who started the visit ----

    private const val APP_INFLUENCE_MS = 15 * 60_000L

    fun initiatorOf(pee: TrackerEvent, events: List<TrackerEvent>): String {
        pee.initiator?.let { return it }
        val influenced = events.any { e ->
            val dt = pee.timestamp - e.timestamp
            when (e.eventType) {
                EventTypes.PROMPT -> dt in 0..APP_INFLUENCE_MS
                // A hold command "releases" the child at the target: going soon after is app-paced.
                EventTypes.COMMAND -> {
                    val release = e.timestamp + (e.holdTargetMin ?: 0) * 60_000L
                    pee.timestamp >= e.timestamp && pee.timestamp - release <= APP_INFLUENCE_MS
                }
                else -> false
            }
        }
        return if (influenced) Initiators.APP else Initiators.CHILD
    }

    // ---- Decision for "now" ----

    data class Decision(
        val action: Action,
        /** SAFETY: current bar for P(void in 5 min); rises as the child succeeds on their own. */
        val safetyThreshold: Double,
        val holdTargetMin: Int?
    )

    private const val HOLD_TRIGGER_SENSATION = 2 // "заметно"

    fun decide(
        settings: Settings,
        probNext5: Double,
        signal: SignalModel.Signal,
        isAsleep: Boolean,
        hasLastVoid: Boolean,
        childSuccessRate: Double?,
        staircase: Staircase,
        bladder: BladderModel.Estimate
    ): Decision {
        val safetyThreshold = (0.5 + 0.4 * (childSuccessRate ?: 0.0)).coerceAtMost(0.9)
        val target = cappedHoldMin(staircase.nextTargetMin, bladder, settings)
        if (isAsleep || !hasLastVoid) return Decision(Action.NONE, safetyThreshold, target)
        val action = when (settings.mode) {
            Mode.QUIET -> Action.NONE
            Mode.SAFETY -> if (probNext5 >= safetyThreshold) Action.PROMPT_GO else Action.NONE
            Mode.HOLD_TRAINING ->
                if (signal.sensation >= HOLD_TRIGGER_SENSATION || probNext5 >= 0.5) Action.COMMAND_HOLD else Action.NONE
        }
        return Decision(action, safetyThreshold, target)
    }
}
