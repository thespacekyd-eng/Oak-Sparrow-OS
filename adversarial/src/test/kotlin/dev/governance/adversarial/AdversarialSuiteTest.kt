package dev.governance.adversarial

import dev.governance.core.*
import dev.governance.attestation.AttestationVerifier
import dev.governance.attestation.EphemeralKeyProvider
import dev.governance.calibration.DefensivePriorCalibrator
import dev.governance.gate.DefaultGovernanceKernel
import dev.governance.metrics.*
import dev.governance.testing.Fixtures
import dev.governance.testing.MutableTestClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant
import java.io.StringWriter
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class AdversarialSuiteTest : FunSpec({

    val fixedSeed = 42L
    val fixedTimestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    fun buildKernel(
        clock: kotlinx.datetime.Clock = kotlinx.datetime.Clock.System,
    ): GovernanceKernel {
        return DefaultGovernanceKernel(
            metrics = GateMetrics(
                dilationFactor = DefaultDilationFactor(),
                predictiveEntropy = DefaultPredictiveEntropy(),
                trajectoryDivergence = DefaultTrajectoryDivergence(),
            ),
            barriers = emptyList(),
            calibrator = DefensivePriorCalibrator(warmupThreshold = 100),
            keyProvider = EphemeralKeyProvider(),
            auditWriter = dev.governance.audit.JsonlAuditWriter(StringWriter()),
            clock = clock,
        )
    }

    fun runAgent(agent: AdversarialAgent, decisions: Int = 200,
                 clock: kotlinx.datetime.Clock = kotlinx.datetime.Clock.System): AdversarialReport {
        return runAdversarial(
            agent = agent,
            kernel = buildKernel(clock),
            initialState = Fixtures.defaultState(gamma = 0.85),
            decisions = decisions,
        )
    }

    test("GradualEscalation: zero false-PASS") {
        val report = runAgent(GradualEscalation(Random(fixedSeed)))
        report.falsePassCount shouldBe 0
    }

    test("CapabilityComposition: zero false-PASS") {
        val report = runAgent(CapabilityComposition(Random(fixedSeed)))
        report.falsePassCount shouldBe 0
    }

    test("TelemetrySpoofing: zero false-PASS") {
        val report = runAgent(TelemetrySpoofing(Random(fixedSeed)))
        report.falsePassCount shouldBe 0
    }

    test("CalibrationPoisoning: zero false-PASS") {
        val report = runAgent(CalibrationPoisoning(Random(fixedSeed)))
        report.falsePassCount shouldBe 0
    }

    test("AuditReplayAttack: zero false-PASS (replays detected)") {
        val clock = MutableTestClock(fixedTimestamp)
        val kernel = buildKernel(clock)
        val state = Fixtures.defaultState(gamma = 0.85, timestamp = fixedTimestamp)

        // Advance clock by 1 second between decisions so timestamps are monotonic
        val wrappedKernel = object : GovernanceKernel {
            override fun decide(state: GovernanceState, proposed: ProposedAction): GateDecision {
                clock.advance(1.seconds)
                return kernel.decide(state, proposed)
            }
            override fun resolve(state: GovernanceState, decision: GateDecision, outcome: ResolvedOutcome) =
                kernel.resolve(state, decision, outcome)
            override fun snapshot(state: GovernanceState) =
                kernel.snapshot(state)
        }

        val report = AuditReplayAttack().run(wrappedKernel, state, decisions = 200)
        report.falsePassCount shouldBe 0
    }

    test("AuditReplayAttack: replayed attestation still cryptographically verifies") {
        val clock = MutableTestClock(fixedTimestamp)
        val kernel = buildKernel(clock)
        var state = Fixtures.defaultState(gamma = 0.85, timestamp = fixedTimestamp)

        // Get a legitimate decision
        clock.advance(1.seconds)
        val action = Fixtures.proposedAction(kind = "read_file")
        val decision = kernel.decide(state, action)
        state = kernel.resolve(state, decision, ResolvedOutcome.BenignSuccess)

        // The attestation is cryptographically valid...
        AttestationVerifier.verify(decision) shouldBe true

        // ...but the sequence number is stale
        decision.sequenceNumber shouldNotBe state.decisionsObserved

        // ...so resolve rejects it
        val result = runCatching { kernel.resolve(state, decision, ResolvedOutcome.BenignSuccess) }
        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "Replay detected"
    }

    test("TimeManipulation: zero false-PASS (clock anomalies detected)") {
        val clock = MutableTestClock(fixedTimestamp)
        val state = Fixtures.defaultState(gamma = 0.85, timestamp = fixedTimestamp)

        val report = runAdversarial(
            agent = TimeManipulation(Random(fixedSeed), clock),
            kernel = buildKernel(clock),
            initialState = state,
            decisions = 200,
        )
        report.falsePassCount shouldBe 0
    }

    test("all adversarial agents complete without exceptions") {
        val agents = listOf(
            GradualEscalation(Random(fixedSeed)),
            CapabilityComposition(Random(fixedSeed)),
            TelemetrySpoofing(Random(fixedSeed)),
            CalibrationPoisoning(Random(fixedSeed)),
        )

        for (agent in agents) {
            val report = runAgent(agent)
            report.totalDecisions shouldBe 200
            report.passCount + report.holdCount + report.vetoCount shouldBe 200
        }
    }
})
