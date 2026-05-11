package dev.governance.android.app.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

/**
 * Contract tests for the [StubLlmEngine] test double itself.
 * Production engines (LlamaCppLlmEngine) get their own
 * instrumented tests since they require native code.
 */
class StubLlmEngineTest : FunSpec({

    test("starts not loaded; loadModel marks it loaded") {
        val stub = StubLlmEngine(defaultResponse = "{}")
        stub.isLoaded shouldBe false
        runBlocking { stub.loadModel() shouldBe null }
        stub.isLoaded shouldBe true
        stub.loadCallCount shouldBe 1
    }

    test("loadModel is idempotent (multiple calls are safe)") {
        val stub = StubLlmEngine(defaultResponse = "{}")
        runBlocking {
            stub.loadModel()
            stub.loadModel()
            stub.loadModel()
        }
        stub.loadCallCount shouldBe 3
        stub.isLoaded shouldBe true
    }

    test("loadShouldFail surfaces the error and leaves isLoaded false") {
        val stub = StubLlmEngine(loadShouldFail = "out of memory")
        runBlocking {
            stub.loadModel() shouldBe "out of memory"
        }
        stub.isLoaded shouldBe false
    }

    test("generate before loadModel throws") {
        val stub = StubLlmEngine(defaultResponse = "{}")
        shouldThrow<IllegalStateException> {
            runBlocking { stub.generate("anything") }
        }
    }

    test("generate routes by substring match in prompt") {
        val stub = StubLlmEngine(
            responses = mapOf(
                "email" to "EMAIL_RESPONSE",
                "calendar" to "CAL_RESPONSE",
            ),
            defaultResponse = "DEFAULT",
        )
        runBlocking {
            stub.loadModel()
            stub.generate("please send an email to chen") shouldBe "EMAIL_RESPONSE"
            stub.generate("check my calendar today") shouldBe "CAL_RESPONSE"
            stub.generate("post to instagram") shouldBe "DEFAULT"
        }
    }

    test("generate logs every prompt for assertion") {
        val stub = StubLlmEngine(defaultResponse = "x")
        runBlocking {
            stub.loadModel()
            stub.generate("first")
            stub.generate("second")
        }
        stub.promptLog shouldHaveSize 2
        stub.promptLog[0] shouldContain "first"
        stub.promptLog[1] shouldContain "second"
    }

    test("generate without responses or default throws to surface test bugs") {
        val stub = StubLlmEngine()
        runBlocking { stub.loadModel() }
        shouldThrow<IllegalStateException> {
            runBlocking { stub.generate("anything") }
        }
    }

    test("close marks engine not-loaded") {
        val stub = StubLlmEngine(defaultResponse = "{}")
        runBlocking { stub.loadModel() }
        stub.isLoaded shouldBe true
        stub.close()
        stub.isLoaded shouldBe false
    }

    test("NoOpLlmEngine is never loaded and reports a friendly load error") {
        NoOpLlmEngine.isLoaded shouldBe false
        NoOpLlmEngine.modelPath() shouldBe null
        NoOpLlmEngine.isModelAvailable() shouldBe true
        runBlocking {
            val msg = NoOpLlmEngine.loadModel()
            msg shouldContain "keyword fallback"
        }
        NoOpLlmEngine.isLoaded shouldBe false
    }

    test("NoOpLlmEngine.generate throws so callers don't accidentally use it") {
        shouldThrow<IllegalStateException> {
            runBlocking { NoOpLlmEngine.generate("anything") }
        }
    }
})
