package dev.governance.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class SafetyMarginTest : StringSpec({

    "all margins are 1.0 when metrics are zero" {
        val m = SafetyMargin.compute(
            effectiveGamma = 0.0, entropy = 0.0, divergence = 0.0,
        )
        m.gammaToVeto shouldBe 1.0
        m.gammaToHold shouldBe 1.0
        m.entropyToHold shouldBe 1.0
        m.divergenceToHold shouldBe 1.0
        m.overall shouldBe 1.0
    }

    "gammaToVeto is zero at VETO threshold" {
        val m = SafetyMargin.compute(
            effectiveGamma = 0.95, entropy = 0.0, divergence = 0.0,
        )
        abs(m.gammaToVeto) shouldBeLessThan 0.001
    }

    "gammaToHold is zero at HOLD threshold" {
        val m = SafetyMargin.compute(
            effectiveGamma = 0.5, entropy = 0.0, divergence = 0.0,
        )
        abs(m.gammaToHold) shouldBeLessThan 0.001
    }

    "margins go negative when metrics exceed thresholds" {
        val m = SafetyMargin.compute(
            effectiveGamma = 1.0, entropy = 0.9, divergence = 0.85,
        )
        m.gammaToVeto shouldBeLessThan 0.0
        m.gammaToHold shouldBeLessThan 0.0
        m.entropyToHold shouldBeLessThan 0.0
        m.divergenceToHold shouldBeLessThan 0.0
        m.overall shouldBeLessThan 0.0
    }

    "overall is min of all margins" {
        val m = SafetyMargin.compute(
            effectiveGamma = 0.3, entropy = 0.8, divergence = 0.1,
        )
        // entropy margin should be the weakest (0.8 > 0.7 threshold)
        m.entropyToHold shouldBeLessThan 0.0
        m.overall shouldBe m.entropyToHold
    }

    "SAFE constant has all margins at 1.0" {
        SafetyMargin.SAFE.overall shouldBe 1.0
        SafetyMargin.SAFE.gammaToVeto shouldBe 1.0
    }
})
