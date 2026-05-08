package dev.governance.testing

import dev.governance.core.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Factory functions for creating test data with sensible defaults. */
object Fixtures {

    /** Default defensive-prior starting state: γ=0.85, narrow envelope, no history. */
    fun defaultState(
        gamma: Double = 0.85,
        timestamp: Instant = Clock.System.now(),
    ): GovernanceState = GovernanceState(
        gamma = gamma,
        referenceEnvelope = defaultReferenceEnvelope(),
        recentHistory = emptyList(),
        decisionsObserved = 0,
        timestamp = timestamp,
    )

    /**
     * Default reference envelope for defensive starting state.
     * Center represents expected steady-state outcome distribution: [passRatio, holdRatio, vetoRatio].
     * Radius is wide initially to avoid a feedback loop where high divergence prevents
     * the kernel from ever PASSing. The calibrator narrows the envelope over time.
     *
     * Placeholder; the initial envelope shape should be formalized before deployment.
     */
    fun defaultReferenceEnvelope(): ReferenceEnvelope = ReferenceEnvelope(
        center = listOf(0.7, 0.2, 0.1),
        radius = 2.0,
        description = "Default defensive-prior envelope (wide radius, centered on expected steady-state distribution)",
    )

    fun proposedAction(
        id: String = "test-action-1",
        kind: String = "read_file",
        reversibility: Reversibility = Reversibility.FullyReversible,
        payload: Map<String, JsonElement> = emptyMap(),
    ): ProposedAction = ProposedAction(
        id = ActionId(id),
        kind = kind,
        reversibility = reversibility,
        payload = payload,
    )

    fun historyEntry(
        actionId: String = "hist-1",
        kind: String = "read_file",
        outcome: Outcome = Outcome.PASS,
        resolvedOutcome: ResolvedOutcome? = ResolvedOutcome.BenignSuccess,
        gamma: Double = 0.5,
        entropy: Double = 0.3,
        divergence: Double = 0.1,
        timestamp: Instant = Clock.System.now(),
    ): HistoryEntry = HistoryEntry(
        actionId = ActionId(actionId),
        kind = kind,
        outcome = outcome,
        resolvedOutcome = resolvedOutcome,
        gamma = gamma,
        entropy = entropy,
        divergence = divergence,
        timestamp = timestamp,
    )

    /** Build a state with N benign history entries and the given γ. */
    fun stateWithHistory(
        gamma: Double,
        historySize: Int,
        timestamp: Instant = Clock.System.now(),
        decisionsObserved: Long = historySize.toLong(),
    ): GovernanceState {
        val history = (1..historySize).map { i ->
            historyEntry(
                actionId = "hist-$i",
                outcome = Outcome.PASS,
                resolvedOutcome = ResolvedOutcome.BenignSuccess,
                gamma = gamma,
                timestamp = timestamp,
            )
        }
        return GovernanceState(
            gamma = gamma,
            referenceEnvelope = defaultReferenceEnvelope(),
            recentHistory = history,
            decisionsObserved = decisionsObserved,
            timestamp = timestamp,
        )
    }

    /** A simple payload map for testing. */
    fun samplePayload(): Map<String, JsonElement> = mapOf(
        "target" to JsonPrimitive("/tmp/test.txt"),
        "mode" to JsonPrimitive("read"),
    )
}
