package dev.governance.android.app.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure-JVM tests for the [VoiceState] state machine. The Android-API
 * pieces (SpeechRecognizer, TextToSpeech) live in [VoiceController]
 * and are exercised via manual smoke testing — see
 * `android/MODEL_SETUP.md`. This file enforces that the legal
 * transitions stay legal and illegal ones stay illegal as the state
 * machine evolves.
 */
class VoiceStateTest : FunSpec({

    test("Idle + Start -> Listening with empty partial") {
        val next = VoiceState.next(VoiceState.Idle, VoiceState.Event.Start)
        next.shouldBeInstanceOf<VoiceState.Listening>()
        next.partial shouldBe ""
    }

    test("Listening + Partial updates partial transcript") {
        val next = VoiceState.next(
            VoiceState.Listening("partial 1"),
            VoiceState.Event.Partial("partial 2"),
        )
        next.shouldBeInstanceOf<VoiceState.Listening>()
        next.partial shouldBe "partial 2"
    }

    test("Listening + Final -> Heard with transcript") {
        val next = VoiceState.next(
            VoiceState.Listening("partial"),
            VoiceState.Event.Final("the full transcript"),
        )
        next.shouldBeInstanceOf<VoiceState.Heard>()
        next.transcript shouldBe "the full transcript"
    }

    test("Heard + SpeakStart -> Speaking") {
        val next = VoiceState.next(
            VoiceState.Heard("hi"),
            VoiceState.Event.SpeakStart("response"),
        )
        next.shouldBeInstanceOf<VoiceState.Speaking>()
        next.text shouldBe "response"
    }

    test("Speaking + SpeakDone -> Idle (full loop closes)") {
        val next = VoiceState.next(
            VoiceState.Speaking("response"),
            VoiceState.Event.SpeakDone,
        )
        next shouldBe VoiceState.Idle
    }

    test("Idle + SpeakStart is allowed (UI feedback before listening)") {
        val next = VoiceState.next(
            VoiceState.Idle,
            VoiceState.Event.SpeakStart("welcome"),
        )
        next.shouldBeInstanceOf<VoiceState.Speaking>()
    }

    test("Cancel from any state returns Idle") {
        listOf<VoiceState>(
            VoiceState.Idle,
            VoiceState.Listening("x"),
            VoiceState.Heard("y"),
            VoiceState.Speaking("z"),
            VoiceState.Error("e"),
        ).forEach { state ->
            VoiceState.next(state, VoiceState.Event.Cancel) shouldBe VoiceState.Idle
        }
    }

    test("Fail from any state -> Error with reason") {
        val next = VoiceState.next(
            VoiceState.Listening("x"),
            VoiceState.Event.Fail("mic broken"),
        )
        next.shouldBeInstanceOf<VoiceState.Error>()
        next.reason shouldBe "mic broken"
    }

    test("Error + Start -> Listening (recover after failure)") {
        val next = VoiceState.next(
            VoiceState.Error("transient"),
            VoiceState.Event.Start,
        )
        next.shouldBeInstanceOf<VoiceState.Listening>()
    }

    test("Listening + Start is rejected (no double-start)") {
        VoiceState.next(VoiceState.Listening("x"), VoiceState.Event.Start) shouldBe null
    }

    test("Listening + SpeakStart is rejected (no overlap)") {
        VoiceState.next(VoiceState.Listening("x"), VoiceState.Event.SpeakStart("y")) shouldBe null
    }

    test("Speaking + Final is rejected") {
        VoiceState.next(VoiceState.Speaking("x"), VoiceState.Event.Final("y")) shouldBe null
    }

    test("Idle + SpeakDone is rejected") {
        VoiceState.next(VoiceState.Idle, VoiceState.Event.SpeakDone) shouldBe null
    }

    test("typical end-to-end loop sequences correctly") {
        // Idle → Listening → Listening (partial) → Heard → Speaking → Idle
        var s: VoiceState = VoiceState.Idle
        s = VoiceState.next(s, VoiceState.Event.Start)!!
        s.shouldBeInstanceOf<VoiceState.Listening>()
        s = VoiceState.next(s, VoiceState.Event.Partial("check my"))!!
        (s as VoiceState.Listening).partial shouldBe "check my"
        s = VoiceState.next(s, VoiceState.Event.Final("check my calendar"))!!
        (s as VoiceState.Heard).transcript shouldBe "check my calendar"
        s = VoiceState.next(s, VoiceState.Event.SpeakStart("Here's your calendar."))!!
        s.shouldBeInstanceOf<VoiceState.Speaking>()
        s = VoiceState.next(s, VoiceState.Event.SpeakDone)!!
        s shouldBe VoiceState.Idle
    }
})
