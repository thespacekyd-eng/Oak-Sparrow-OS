package dev.governance.metrics

import dev.governance.core.*
import dev.governance.testing.Fixtures
import dev.governance.testing.PropertyGenerators
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.datetime.Instant

class MetricsPropertyTest : FunSpec({

    val fixedTimestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    // -----------------------------------------------------------------------
    // DilationFactor
    // -----------------------------------------------------------------------

    test("DilationFactor: output always in [0, 1] - property test") {
        val df = DefaultDilationFactor()
        checkAll(200, PropertyGenerators.arbGovernanceState()) { state ->
            val result = df.compute(state)
            result shouldBeGreaterThanOrEqual 0.0
            result shouldBeLessThanOrEqual 1.0
        }
    }

    test("DilationFactor: sequence of Flagged outcomes monotonically raises gamma") {
        val df = DefaultDilationFactor()
        val history = mutableListOf<HistoryEntry>()
        var lastGamma = 0.0

        repeat(20) { i ->
            history.add(
                HistoryEntry(
                    actionId = ActionId("mono-$i"),
                    kind = "test",
                    outcome = Outcome.PASS,
                    resolvedOutcome = ResolvedOutcome.Flagged("incident"),
                    gamma = 0.5,
                    timestamp = fixedTimestamp,
                )
            )
            val state = GovernanceState(
                gamma = 0.5,
                referenceEnvelope = Fixtures.defaultReferenceEnvelope(),
                recentHistory = history.toList(),
                decisionsObserved = history.size.toLong(),
                timestamp = fixedTimestamp,
            )
            val currentGamma = df.compute(state)
            currentGamma shouldBeGreaterThanOrEqual lastGamma - 1e-10
            lastGamma = currentGamma
        }
    }

    test("DilationFactor: deterministic for identical inputs") {
        val df = DefaultDilationFactor()
        val state = Fixtures.stateWithHistory(gamma = 0.5, historySize = 10, timestamp = fixedTimestamp)
        val r1 = df.compute(state)
        val r2 = df.compute(state)
        r1 shouldBe r2
    }

    test("DilationFactor: empty history returns current gamma") {
        val df = DefaultDilationFactor()
        val state = Fixtures.defaultState(gamma = 0.42).copy(timestamp = fixedTimestamp)
        df.compute(state) shouldBe 0.42
    }

    // -----------------------------------------------------------------------
    // PredictiveEntropy
    // -----------------------------------------------------------------------

    test("PredictiveEntropy: output always in [0, 1] - property test") {
        val pe = DefaultPredictiveEntropy()
        checkAll(200, PropertyGenerators.arbGovernanceState()) { state ->
            val result = pe.estimate(state)
            result shouldBeGreaterThanOrEqual 0.0
            result shouldBeLessThanOrEqual 1.0
        }
    }

    test("PredictiveEntropy: single action kind gives entropy 0") {
        val pe = DefaultPredictiveEntropy()
        val history = (1..10).map {
            Fixtures.historyEntry(kind = "read_file", timestamp = fixedTimestamp)
        }
        val state = Fixtures.defaultState().copy(
            recentHistory = history,
            timestamp = fixedTimestamp,
        )
        pe.estimate(state) shouldBe 0.0
    }

    test("PredictiveEntropy: uniform distribution gives entropy near 1") {
        val pe = DefaultPredictiveEntropy()
        val kinds = listOf("a", "b", "c", "d", "e")
        val history = kinds.flatMap { kind ->
            (1..10).map { Fixtures.historyEntry(kind = kind, timestamp = fixedTimestamp) }
        }
        val state = Fixtures.defaultState().copy(
            recentHistory = history,
            timestamp = fixedTimestamp,
        )
        val entropy = pe.estimate(state)
        entropy shouldBeGreaterThan 0.9
    }

    test("PredictiveEntropy: deterministic for identical inputs") {
        val pe = DefaultPredictiveEntropy()
        val state = Fixtures.stateWithHistory(gamma = 0.5, historySize = 10, timestamp = fixedTimestamp)
        pe.estimate(state) shouldBe pe.estimate(state)
    }

    // -----------------------------------------------------------------------
    // TrajectoryDivergence
    // -----------------------------------------------------------------------

    test("TrajectoryDivergence: output always in [0, 1] - property test") {
        val td = DefaultTrajectoryDivergence()
        checkAll(200, PropertyGenerators.arbGovernanceState()) { state ->
            val result = td.measure(state, state.referenceEnvelope)
            result shouldBeGreaterThanOrEqual 0.0
            result shouldBeLessThanOrEqual 1.0
        }
    }

    test("TrajectoryDivergence: empty history gives 0") {
        val td = DefaultTrajectoryDivergence()
        val state = Fixtures.defaultState()
        td.measure(state, state.referenceEnvelope) shouldBe 0.0
    }

    test("TrajectoryDivergence: history matching reference center gives low divergence") {
        val td = DefaultTrajectoryDivergence()
        // Reference center is [0.7, 0.2, 0.1]
        // Create 50 entries matching the distribution exactly: 35 PASS, 10 HOLD, 5 VETO
        val history = (1..35).map {
            Fixtures.historyEntry(outcome = Outcome.PASS, timestamp = fixedTimestamp)
        } + (1..10).map {
            Fixtures.historyEntry(outcome = Outcome.HOLD, timestamp = fixedTimestamp)
        } + (1..5).map {
            Fixtures.historyEntry(outcome = Outcome.VETO, timestamp = fixedTimestamp)
        }
        val state = Fixtures.defaultState().copy(
            recentHistory = history,
            timestamp = fixedTimestamp,
        )
        val divergence = td.measure(state, state.referenceEnvelope)
        divergence shouldBeLessThanOrEqual 0.1
    }

    test("TrajectoryDivergence: deterministic for identical inputs") {
        val td = DefaultTrajectoryDivergence()
        val state = Fixtures.stateWithHistory(gamma = 0.5, historySize = 10, timestamp = fixedTimestamp)
        td.measure(state, state.referenceEnvelope) shouldBe td.measure(state, state.referenceEnvelope)
    }
})
