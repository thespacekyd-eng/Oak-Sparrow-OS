package dev.governance.gate

import dev.governance.core.*
import dev.governance.attestation.EphemeralKeyProvider
import dev.governance.calibration.DefensivePriorCalibrator
import dev.governance.metrics.*
import dev.governance.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import kotlinx.serialization.json.Json
import java.io.StringWriter

class SnapshotTest : FunSpec({

    fun buildKernel(warmupThreshold: Int = 100): DefaultGovernanceKernel {
        return DefaultGovernanceKernel(
            metrics = GateMetrics(
                dilationFactor = DefaultDilationFactor(),
                predictiveEntropy = DefaultPredictiveEntropy(),
                trajectoryDivergence = DefaultTrajectoryDivergence(),
            ),
            barriers = emptyList(),
            calibrator = DefensivePriorCalibrator(warmupThreshold = warmupThreshold),
            keyProvider = EphemeralKeyProvider(),
            auditWriter = dev.governance.audit.JsonlAuditWriter(StringWriter()),
        )
    }

    test("snapshot reflects current state accurately") {
        val kernel = buildKernel()
        val state = Fixtures.stateWithHistory(gamma = 0.42, historySize = 10)

        val snapshot = kernel.snapshot(state)
        snapshot.gamma shouldBe 0.42
        snapshot.warmupComplete shouldBe false // default warmupThreshold=100, only 10 decisions
    }

    test("snapshot warmupComplete flag reflects calibrator mode") {
        val kernel = buildKernel(warmupThreshold = 5)
        val state = Fixtures.defaultState()

        // Before warmup
        kernel.snapshot(state).warmupComplete shouldBe false

        // Run enough decisions to complete warmup
        var s = state
        repeat(6) {
            val action = Fixtures.proposedAction(id = "warmup-$it")
            val decision = kernel.decide(s, action)
            s = kernel.resolve(s, decision, ResolvedOutcome.BenignSuccess)
        }

        kernel.snapshot(s).warmupComplete shouldBe true
    }

    test("snapshot is serializable to JSON and round-trips") {
        val kernel = buildKernel()
        val state = Fixtures.stateWithHistory(gamma = 0.5, historySize = 5)
        val snapshot = kernel.snapshot(state)

        val json = Json { prettyPrint = false; encodeDefaults = true }
        val serialized = json.encodeToString(GovernanceSnapshot.serializer(), snapshot)
        val deserialized = json.decodeFromString(GovernanceSnapshot.serializer(), serialized)

        deserialized.gamma shouldBe snapshot.gamma
        deserialized.warmupComplete shouldBe snapshot.warmupComplete
        deserialized.recentOutcomes.pass shouldBe snapshot.recentOutcomes.pass
        deserialized.recentOutcomes.hold shouldBe snapshot.recentOutcomes.hold
        deserialized.recentOutcomes.veto shouldBe snapshot.recentOutcomes.veto
    }

    test("snapshot entropy and divergence averages are in bounds") {
        val kernel = buildKernel()
        val state = Fixtures.stateWithHistory(gamma = 0.5, historySize = 20)

        val snapshot = kernel.snapshot(state)
        snapshot.recentEntropyAverage shouldBeGreaterThanOrEqual 0.0
        snapshot.recentDivergenceAverage shouldBeGreaterThanOrEqual 0.0
    }
})
