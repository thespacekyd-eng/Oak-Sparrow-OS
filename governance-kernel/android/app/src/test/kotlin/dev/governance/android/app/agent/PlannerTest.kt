package dev.governance.android.app.agent

import dev.governance.core.Reversibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for [Planner] pure logic: keyword routing,
 * LLM response parsing, target extraction, and action-kind
 * validation. All tests use companion object functions —
 * no Android Context required.
 */
class PlannerTest : FunSpec({

    // -- planWithKeywords routing --

    test("planWithKeywords routes 'check my calendar' to read_calendar") {
        val result = Planner.planWithKeywords("check my calendar")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps.size shouldBe 1
        result.plan.steps[0].kind shouldBe "read_calendar"
        result.plan.steps[0].reversibility shouldBe Reversibility.FullyReversible
    }

    test("planWithKeywords routes 'email chen' to send_email with target=chen") {
        val result = Planner.planWithKeywords("email to chen saying hello")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps[0].kind shouldBe "send_email"
        result.plan.steps[0].target shouldBe "chen"
        result.plan.steps[0].reversibility shouldBe Reversibility.OneShot
    }

    test("planWithKeywords routes 'share to instagram' to share_to_social_app") {
        val result = Planner.planWithKeywords("share this to instagram")
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps[0].kind shouldBe "share_to_social_app"
    }

    test("planWithKeywords returns Error for unsupported instructions") {
        val result = Planner.planWithKeywords("translate this paragraph to French")
        result.shouldBeInstanceOf<PlanResult.Error>()
        result.message shouldContain "calendar"
    }

    test("planWithKeywords routes open-app and unsupported read correctly") {
        // "open the settings app" now correctly routes to open_app
        val openResult = Planner.planWithKeywords("open the settings app")
        openResult.shouldBeInstanceOf<PlanResult.Success>()
        openResult.plan.steps[0].kind shouldBe "open_app"

        // Unsupported instruction still returns Error
        val readResult = Planner.planWithKeywords("read that document")
        readResult.shouldBeInstanceOf<PlanResult.Error>()
    }

    // -- parseResponse --

    test("parseResponse accepts valid LLM JSON for send_email") {
        val json = """{"summary":"Send email","steps":[{"kind":"send_email","target":"chen","rationale":"notify","reversibility":"OneShot"}]}"""
        val result = Planner.parseResponse(json)
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.summary shouldBe "Send email"
        result.plan.steps[0].kind shouldBe "send_email"
        result.plan.steps[0].reversibility shouldBe Reversibility.OneShot
    }

    test("parseResponse rejects unsupported action kind") {
        val json = """{"summary":"Delete","steps":[{"kind":"delete_file","target":"foo.txt","rationale":"delete","reversibility":"Irreversible"}]}"""
        val result = Planner.parseResponse(json)
        result.shouldBeInstanceOf<PlanResult.Error>()
        result.message shouldContain "delete file"
    }

    test("parseResponse handles malformed JSON as conversational") {
        // No closing brace → no JSON extracted → treated as conversational text
        val result = Planner.parseResponse("not json at all {{{")
        result.shouldBeInstanceOf<PlanResult.Conversational>()
    }

    test("parseResponse handles 'unsupported' summary as Error") {
        val json = """{"summary":"unsupported","steps":[]}"""
        val result = Planner.parseResponse(json)
        result.shouldBeInstanceOf<PlanResult.Error>()
        result.message shouldContain "calendar"
    }

    test("parseResponse extracts multi-step plan") {
        val json = """{"summary":"Multi","steps":[{"kind":"read_calendar","target":null,"rationale":"check","reversibility":"FullyReversible"},{"kind":"send_email","target":"bob","rationale":"notify","reversibility":"OneShot"}]}"""
        val result = Planner.parseResponse(json)
        result.shouldBeInstanceOf<PlanResult.Success>()
        result.plan.steps.size shouldBe 2
    }

    // -- extractTarget --

    test("extractTarget finds name after 'to'") {
        Planner.extractTarget("send email to chen") shouldBe "chen"
    }

    test("extractTarget finds name after 'for'") {
        Planner.extractTarget("schedule meeting for alice") shouldBe "alice"
    }

    test("extractTarget returns empty when no target marker") {
        Planner.extractTarget("check my calendar") shouldBe ""
    }

    // -- buildPrompt --

    test("buildPrompt includes supported actions list") {
        val prompt = Planner.buildPrompt("test instruction")
        prompt shouldContain "read_calendar"
        prompt shouldContain "send_email"
        prompt shouldContain "share_to_social_app"
        prompt shouldContain "ONLY use these actions"
    }

    // -- AuthorizationResultBridge --

    test("AuthorizationResultBridge timeout returns false") {
        AuthorizationResultBridge.reset()
        val result = runBlocking {
            AuthorizationResultBridge.awaitResult(timeoutMs = 200)
        }
        result shouldBe false
    }

    test("AuthorizationResultBridge approve returns true") {
        AuthorizationResultBridge.reset()
        // Deliver result before awaiting
        AuthorizationResultBridge.deliverResult(true)
        val result = runBlocking {
            AuthorizationResultBridge.awaitResult(timeoutMs = 1000)
        }
        result shouldBe true
    }

    test("AuthorizationResultBridge skip returns false") {
        AuthorizationResultBridge.reset()
        AuthorizationResultBridge.deliverResult(false)
        val result = runBlocking {
            AuthorizationResultBridge.awaitResult(timeoutMs = 1000)
        }
        result shouldBe false
    }
})
