package dev.governance.anomaly

import dev.governance.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.Instant

class BehaviorAnomalyLayerTest : FunSpec({

    // ── Fixtures ────────────────────────────────────────────────────────────

    fun entry(
        outcome: Outcome = Outcome.PASS,
        gamma: Double = 0.10,
        entropy: Double = 0.20,
        divergence: Double = 0.10,
        resolved: ResolvedOutcome? = ResolvedOutcome.BenignSuccess,
        secondsOffset: Long = 0L,
    ) = HistoryEntry(
        actionId = ActionId("a"),
        kind = "test_action",
        outcome = outcome,
        resolvedOutcome = resolved,
        gamma = gamma,
        entropy = entropy,
        divergence = divergence,
        timestamp = Instant.fromEpochSeconds(secondsOffset),
    )

    /** Steady normal behavior: all PASS, low gamma/entropy/divergence. */
    fun normalHistory(size: Int): List<HistoryEntry> =
        (0 until size).map { i ->
            entry(
                outcome = Outcome.PASS,
                gamma = 0.10 + (i % 5) * 0.01,
                entropy = 0.20 + (i % 3) * 0.02,
                divergence = 0.10,
                secondsOffset = i * 60L,
            )
        }

    /**
     * Attack suffix: escalating VETOs, high gamma/entropy, Flagged outcomes.
     * Mimics calibration-poisoning or gradual-escalation adversarial strategies.
     */
    fun attackSuffix(size: Int, startOffset: Long): List<HistoryEntry> =
        (0 until size).map { i ->
            entry(
                outcome = if (i % 2 == 0) Outcome.VETO else Outcome.HOLD,
                gamma = (0.75 + i * 0.02).coerceAtMost(1.0),
                entropy = 0.85,
                divergence = 0.80,
                resolved = ResolvedOutcome.Flagged("attack_$i"),
                secondsOffset = startOffset + i * 30L,
            )
        }

    fun attackHistory(normalSize: Int, attackSize: Int): List<HistoryEntry> {
        val normal = normalHistory(normalSize)
        val attack = attackSuffix(attackSize, startOffset = normalSize * 60L)
        return normal + attack
    }

    /** Mechanical/scripted behavior: identical outcomes repeated, zero variance. */
    fun mechanicalHistory(size: Int): List<HistoryEntry> =
        (0 until size).map { i ->
            entry(
                outcome = Outcome.PASS,
                gamma = 0.15,
                entropy = 0.30,
                divergence = 0.15,
                secondsOffset = i * 10L,
            )
        }

    // ── BehaviorFeature ─────────────────────────────────────────────────────

    context("BehaviorFeature.encode") {

        test("PASS encodes as outcome dimension 0.0") {
            BehaviorFeature.encode(entry(outcome = Outcome.PASS), null)[0] shouldBe 0.0
        }

        test("HOLD encodes as outcome dimension 0.5") {
            BehaviorFeature.encode(entry(outcome = Outcome.HOLD), null)[0] shouldBe 0.5
        }

        test("VETO encodes as outcome dimension 1.0") {
            BehaviorFeature.encode(entry(outcome = Outcome.VETO), null)[0] shouldBe 1.0
        }

        test("Flagged resolved outcome encodes as 1.0 in dimension 4") {
            val vec = BehaviorFeature.encode(entry(resolved = ResolvedOutcome.Flagged("x")), null)
            vec[4] shouldBe 1.0
        }

        test("BenignSuccess encodes as 0.0 in dimension 4") {
            val vec = BehaviorFeature.encode(entry(resolved = ResolvedOutcome.BenignSuccess), null)
            vec[4] shouldBe 0.0
        }

        test("all values lie within [0, 1]") {
            val vec = BehaviorFeature.encode(
                entry(gamma = 0.7, entropy = 0.8, divergence = 0.6),
                Instant.fromEpochSeconds(0),
            )
            vec.forEach { v ->
                v shouldBeGreaterThan -0.001
                v shouldBeLessThan 1.001
            }
        }

        test("vector has exactly BehaviorFeature.DIM elements") {
            val vec = BehaviorFeature.encode(entry(), null)
            vec.size shouldBe BehaviorFeature.DIM
        }
    }

    // ── WindowExtractor ─────────────────────────────────────────────────────

    context("WindowExtractor") {

        test("returns empty when history is shorter than windowSize") {
            val windows = WindowExtractor.extract(normalHistory(5), windowSize = 10)
            windows shouldBe emptyList()
        }

        test("extracts at least one window when history equals windowSize") {
            val windows = WindowExtractor.extract(normalHistory(10), windowSize = 10)
            windows.size shouldBe 1
        }

        test("embedding has correct length (windowSize × DIM)") {
            val ws = 10
            val windows = WindowExtractor.extract(normalHistory(ws), windowSize = ws)
            windows.first().embedding.size shouldBe ws * BehaviorFeature.DIM
        }

        test("window indices are sequential starting from 0") {
            val windows = WindowExtractor.extract(normalHistory(30), windowSize = 10, stride = 5)
            windows.mapIndexed { i, w -> w.windowIndex shouldBe i }
        }
    }

    // ── BehaviorAnomalyLayer — insufficient history ──────────────────────────

    context("BehaviorAnomalyLayer with insufficient history") {

        test("returns EMPTY when history is shorter than minHistorySize") {
            val layer = BehaviorAnomalyLayer(windowSize = 20)
            layer.analyze(normalHistory(10)) shouldBe AnomalyReport.EMPTY
        }

        test("returns EMPTY for empty history") {
            BehaviorAnomalyLayer().analyze(emptyList<HistoryEntry>()) shouldBe AnomalyReport.EMPTY
        }
    }

    // ── BehaviorAnomalyLayer — normal behavior ───────────────────────────────

    context("BehaviorAnomalyLayer on normal behavior") {

        test("does not flag uniform normal behavior as high-confidence anomaly") {
            val layer = BehaviorAnomalyLayer(windowSize = 10, anomalyPercentile = 92.0)
            val report = layer.analyze(normalHistory(80))
            report.totalWindows shouldBeGreaterThan 0
            // With uniform behavior the top window's confidence stays low
            report.topConfidence shouldBeLessThan 0.95
        }

        test("report contains scored windows") {
            val layer = BehaviorAnomalyLayer(windowSize = 10)
            val report = layer.analyze(normalHistory(60))
            report.scoredWindows.size shouldBeGreaterThan 0
        }
    }

    // ── BehaviorAnomalyLayer — attack behavior ────────────────────────────────

    context("BehaviorAnomalyLayer detects attack patterns") {

        test("flags calibration-poisoning style attack (high veto + flagged rate)") {
            val layer = BehaviorAnomalyLayer(windowSize = 10, anomalyPercentile = 90.0)
            val report = layer.analyze(attackHistory(normalSize = 50, attackSize = 20))
            report.isAnomalous shouldBe true
        }

        test("top-ranked window has higher confidence than bottom-ranked window") {
            val layer = BehaviorAnomalyLayer(windowSize = 10, stride = 5, anomalyPercentile = 85.0)
            val report = layer.analyze(attackHistory(normalSize = 40, attackSize = 20))
            val windows = report.scoredWindows
            windows.size shouldBeGreaterThan 1
            windows.first().confidence shouldBeGreaterThan windows.last().confidence
        }

        test("attack report totalWindows matches expected extraction count") {
            val history = attackHistory(normalSize = 50, attackSize = 20)
            val layer = BehaviorAnomalyLayer(windowSize = 10, stride = 5)
            val report = layer.analyze(history)
            val expected = WindowExtractor.extract(history, 10, 5).size
            report.totalWindows shouldBe expected
        }
    }

    // ── BehaviorAnomalyScorer metrics ────────────────────────────────────────

    context("BehaviorAnomalyScorer individual metrics") {

        val normalHistory = normalHistory(40)
        val attackHistory = attackHistory(normalSize = 40, attackSize = 10)

        val normalWindows = WindowExtractor.extract(normalHistory, windowSize = 10, stride = 5)
        val attackWindows = WindowExtractor.extract(attackHistory, windowSize = 10, stride = 5)
        val scorer = BehaviorAnomalyScorer(normalWindows)

        test("contextual score is higher for veto-heavy window than baseline") {
            val normalScore  = scorer.contextualScore(normalWindows.first())
            val attackWindow = attackWindows.last()
            scorer.contextualScore(attackWindow) shouldBeGreaterThan normalScore
        }

        test("MSE score is higher for attack window than baseline window") {
            val normalScore  = scorer.mseScore(normalWindows.first())
            val attackWindow = attackWindows.last()
            scorer.mseScore(attackWindow) shouldBeGreaterThan normalScore
        }

        test("pattern regularity score is high for mechanical behavior") {
            val mechanicalWindows = WindowExtractor.extract(mechanicalHistory(20), windowSize = 10)
            val mechanicalScorer  = BehaviorAnomalyScorer(mechanicalWindows)
            val score = mechanicalScorer.patternRegularityScore(mechanicalWindows.first())
            score shouldBeGreaterThan 0.5
        }

        test("gradient score is near zero for stable behavior") {
            val score = scorer.gradientScore(normalWindows.first())
            score shouldBeLessThan 0.5
        }
    }

    // ── normalizeAndRank ─────────────────────────────────────────────────────

    context("BehaviorAnomalyScorer.normalizeAndRank") {

        test("all normalized scores lie within [0, 1]") {
            val history = attackHistory(normalSize = 30, attackSize = 10)
            val windows = WindowExtractor.extract(history, windowSize = 10, stride = 5)
            val scorer  = BehaviorAnomalyScorer(windows.take(windows.size / 2))
            val ranked  = scorer.normalizeAndRank(windows.map { scorer.score(it) })
            ranked.forEach { sw ->
                sw.confidence   shouldBeGreaterThan -0.001
                sw.confidence   shouldBeLessThan  1.001
                sw.mseNorm      shouldBeGreaterThan -0.001
                sw.contextualNorm shouldBeGreaterThan -0.001
            }
        }

        test("returns same count as input") {
            val history = normalHistory(40)
            val windows = WindowExtractor.extract(history, windowSize = 10, stride = 5)
            val scorer  = BehaviorAnomalyScorer(windows)
            val ranked  = scorer.normalizeAndRank(windows.map { scorer.score(it) })
            ranked.size shouldBe windows.size
        }
    }

    // ── Configuration validation ──────────────────────────────────────────────

    context("BehaviorAnomalyLayer configuration") {

        test("custom windowSize and stride are applied") {
            val layer = BehaviorAnomalyLayer(windowSize = 5, stride = 2)
            val history = normalHistory(20)
            val report = layer.analyze(history)
            val expected = WindowExtractor.extract(history, 5, 2).size
            report.totalWindows shouldBe expected
        }

        test("highConfidenceThreshold 1.0 means isAnomalous is always false") {
            val layer = BehaviorAnomalyLayer(
                windowSize = 10,
                anomalyPercentile = 80.0,
                highConfidenceThreshold = 1.0,
            )
            val report = layer.analyze(attackHistory(normalSize = 40, attackSize = 20))
            report.isAnomalous shouldBe false
        }
    }
})
