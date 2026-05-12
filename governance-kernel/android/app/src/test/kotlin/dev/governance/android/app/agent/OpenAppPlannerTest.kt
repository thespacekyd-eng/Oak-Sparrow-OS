package dev.governance.android.app.agent

import dev.governance.core.Reversibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for the open_app keyword route added in Phase 4A
 * (zero-latency speculative dispatch). Verifies that "open X",
 * "launch X", "pull up X", "show me X" all resolve to an
 * open_app step with the right target.
 */
class OpenAppPlannerTest : FunSpec({

    test("'pull up instagram' routes to open_app with target=instagram") {
        val result = Planner.planWithKeywords("pull up instagram")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps.size shouldBe 1
        result.plan.steps[0].kind shouldBe "open_app"
        result.plan.steps[0].target shouldBe "instagram"
        result.plan.steps[0].reversibility shouldBe Reversibility.FullyReversible
    }

    test("'open chrome' routes to open_app with target=chrome") {
        val result = Planner.planWithKeywords("open chrome")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps[0].kind shouldBe "open_app"
        result.plan.steps[0].target shouldBe "chrome"
    }

    test("'launch youtube' routes to open_app") {
        val result = Planner.planWithKeywords("launch youtube please")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps[0].kind shouldBe "open_app"
        result.plan.steps[0].target shouldBe "youtube"
    }

    test("'show me maps' routes to open_app") {
        val result = Planner.planWithKeywords("show me maps")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps[0].kind shouldBe "open_app"
        result.plan.steps[0].target shouldBe "maps"
    }

    test("open_app reversibility is always FullyReversible") {
        val result = Planner.planWithKeywords("open settings")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps[0].reversibility shouldBe Reversibility.FullyReversible
    }

    test("'pull up instagram' beats the share-keyword 'instagram' rule") {
        // The share rule also keys on 'instagram' but the open_intent
        // check runs first, so this should land as open_app, not share.
        val result = Planner.planWithKeywords("pull up instagram")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps[0].kind shouldBe "open_app"
    }

    test("'share to instagram' still routes to share_to_social_app") {
        // Regression check — the existing share path must still work.
        val result = Planner.planWithKeywords("share this to instagram")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps[0].kind shouldBe "share_to_social_app"
    }

    test("open_app is in SUPPORTED_KINDS") {
        Planner.SUPPORTED_KINDS.contains("open_app") shouldBe true
    }

    test("'open' without a known app falls through to error") {
        val result = Planner.planWithKeywords("open the door")
        // 'door' is not in APP_TRIGGERS — falls through to UNSUPPORTED_MSG.
        result.shouldBeInstanceOf<PlanResult.Error>()
    }
})
