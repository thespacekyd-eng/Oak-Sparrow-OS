package dev.governance.android.app.agent

import dev.governance.core.Reversibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking

/**
 * Integration tests for [Planner] + [LlmEngine]. Drives the full
 * prompt-build → engine.generate → parseResponse pipeline through
 * the [StubLlmEngine] test double, with no Android Context required.
 *
 * Companion-object-only logic (planWithKeywords, parseResponse,
 * extractTarget, buildPrompt) is covered separately in PlannerTest;
 * this file focuses on the wiring between Planner and the engine.
 */
class LlmPlannerTest : FunSpec({

    test("plan() falls back to keywords when engine is not loaded") {
        val stub = StubLlmEngine(defaultResponse = """{"summary":"x","steps":[]}""")
        val planner = Planner(stub)
        // engine.isLoaded == false because we never called loadModel
        runBlocking {
            val result = planner.plan("check my calendar")
            result.shouldBeInstanceOf<PlanResult.Success>()
            result.plan.steps[0].kind shouldBe "read_calendar"
        }
        // Engine was never asked to generate — keyword router handled it
        stub.promptLog.size shouldBe 0
    }

    test("plan() routes through engine once loaded and parses success") {
        // Use an instruction the keyword router can't handle so it falls through to the engine
        val cannedJson = """{"summary":"Create event with chen","steps":[{"kind":"create_event","target":"meeting with chen","rationale":"User asked","reversibility":"PartiallyReversible"}]}"""
        val stub = StubLlmEngine(defaultResponse = cannedJson)
        val planner = Planner(stub)
        runBlocking {
            planner.loadModel() shouldBe null
            val result = planner.plan("put a meeting with chen on my agenda tomorrow")
            result.shouldBeInstanceOf<PlanResult.Success>()
            result.plan.summary shouldContain "chen"
            result.plan.steps[0].kind shouldBe "create_event"
        }
        // Engine WAS asked to generate — keyword router couldn't handle this
        stub.promptLog.size shouldBe 1
        stub.promptLog[0] shouldContain "meeting with chen"
    }

    test("plan() returns Conversational when engine returns plain text") {
        val stub = StubLlmEngine(defaultResponse = "I can help with that!")
        val planner = Planner(stub)
        runBlocking {
            planner.loadModel()
            // Instruction keyword router can't handle → falls to engine
            val result = planner.plan("what can you do for me")
            result.shouldBeInstanceOf<PlanResult.Conversational>()
            result.message shouldContain "help"
        }
    }

    test("plan() returns Error when engine returns 'unsupported' summary") {
        val stub = StubLlmEngine(defaultResponse = """{"summary":"unsupported","steps":[]}""")
        val planner = Planner(stub)
        runBlocking {
            planner.loadModel()
            val result = planner.plan("hack the pentagon")
            result.shouldBeInstanceOf<PlanResult.Error>()
            result.message shouldContain "open apps"
        }
    }

    test("plan() returns Error when engine emits an unsupported action kind") {
        val cannedJson = """{"summary":"Run shell","steps":[{"kind":"shell_exec","target":"ls","rationale":"x","reversibility":"OneShot"}]}"""
        val stub = StubLlmEngine(defaultResponse = cannedJson)
        val planner = Planner(stub)
        runBlocking {
            planner.loadModel()
            val result = planner.plan("run ls")
            result.shouldBeInstanceOf<PlanResult.Error>()
            result.message shouldContain "shell exec"
        }
    }

    test("plan() catches engine exceptions and returns Error") {
        // StubLlmEngine throws when no canned response and no default
        val stub = StubLlmEngine(responses = emptyMap(), defaultResponse = null)
        val planner = Planner(stub)
        runBlocking {
            planner.loadModel()
            val result = planner.plan("anything")
            result.shouldBeInstanceOf<PlanResult.Error>()
            result.message shouldContain "LLM inference failed"
        }
    }

    test("loadModel() failure surfaces as the message; planner falls back to keywords") {
        val stub = StubLlmEngine(loadShouldFail = "out of memory")
        val planner = Planner(stub)
        runBlocking {
            val loadErr = planner.loadModel()
            loadErr shouldBe "out of memory"
            // Engine still not loaded → keyword fallback fires
            val result = planner.plan("check calendar")
            result.shouldBeInstanceOf<PlanResult.Success>()
            result.plan.steps[0].kind shouldBe "read_calendar"
        }
    }

    test("close() releases the engine") {
        val stub = StubLlmEngine(defaultResponse = "{}")
        val planner = Planner(stub)
        runBlocking { planner.loadModel() }
        stub.isLoaded shouldBe true
        planner.close()
        stub.isLoaded shouldBe false
    }

    test("modelPath and isModelAvailable delegate to the engine") {
        val planner = Planner(NoOpLlmEngine)
        planner.modelPath() shouldBe null
        planner.isModelAvailable() shouldBe true
    }

    test("prompt sent to engine is the buildPrompt template (contains supported actions list)") {
        // Use an instruction the keyword router can't handle so it reaches the engine
        val stub = StubLlmEngine(defaultResponse = """{"summary":"Summarize inbox","steps":[{"kind":"read_calendar","target":null,"rationale":"x","reversibility":"FullyReversible"}]}""")
        val planner = Planner(stub)
        runBlocking {
            planner.loadModel()
            planner.plan("summarize my day ahead")
        }
        val prompt = stub.promptLog.single()
        prompt shouldContain "read_calendar"
        prompt shouldContain "send_email"
        prompt shouldContain "share_to_social_app"
        prompt shouldContain "open_app"
        prompt shouldContain "summarize my day ahead"
    }
})
