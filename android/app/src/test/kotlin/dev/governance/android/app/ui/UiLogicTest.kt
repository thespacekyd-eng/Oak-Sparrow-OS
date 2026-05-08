package dev.governance.android.app.ui

import dev.governance.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.hours

/**
 * Unit tests for UI logic: action templates, attention derivation,
 * and reversibility-to-verification-line mapping.
 */
class UiLogicTest : FunSpec({

    // -- ActionTemplates --

    val allKinds = listOf(
        "send_email", "post_social", "schedule_event", "delete_file",
        "send_message", "make_payment", "read_file", "read_contacts",
        "write_file", "open_app", "create_account", "sign_document",
    )

    test("every registered kind returns a non-zero string resource ID") {
        allKinds.forEach { kind ->
            val resId = ActionTemplates.questionResId(kind)
            (resId != 0) shouldBe true
        }
    }

    test("unknown kind returns fallback resource ID") {
        val resId = ActionTemplates.questionResId("unknown_action_xyz")
        (resId != 0) shouldBe true
    }

    test("pastTenseLabel returns non-empty for all registered kinds") {
        allKinds.forEach { kind ->
            ActionTemplates.pastTenseLabel(kind).shouldNotBeEmpty()
        }
    }

    test("pastTenseLabel for unknown kind replaces underscores") {
        ActionTemplates.pastTenseLabel("do_something") shouldBe "do something"
    }

    test("outcomeLabel maps all three outcomes") {
        ActionTemplates.outcomeLabel(Outcome.PASS) shouldBe "approved"
        ActionTemplates.outcomeLabel(Outcome.HOLD) shouldBe "asked you"
        ActionTemplates.outcomeLabel(Outcome.VETO) shouldBe "blocked"
    }

    // -- needsAttention derivation --

    fun needsAttention(snapshot: GovernanceSnapshot?, errorCount: Int): Boolean {
        return snapshot != null && (snapshot.gamma > 0.6 || errorCount > 0)
    }

    test("null snapshot does not need attention") {
        needsAttention(null, 0) shouldBe false
    }

    test("low gamma and zero errors does not need attention") {
        val snap = makeSnapshot(gamma = 0.3)
        needsAttention(snap, 0) shouldBe false
    }

    // gamma > 0.6 threshold: 0.6 itself is NOT attention (> not >=)
    test("gamma at exactly 0.6 does not need attention") {
        val snap = makeSnapshot(gamma = 0.6)
        needsAttention(snap, 0) shouldBe false
    }

    test("gamma above 0.6 needs attention") {
        val snap = makeSnapshot(gamma = 0.61)
        needsAttention(snap, 0) shouldBe true
    }

    test("any errors need attention regardless of gamma") {
        val snap = makeSnapshot(gamma = 0.1)
        needsAttention(snap, 1) shouldBe true
    }

    // -- Reversibility-to-verification-line mapping --

    fun verificationLine(reversibility: Reversibility): String {
        return if (reversibility == Reversibility.OneShot ||
            reversibility == Reversibility.Irreversible
        ) {
            "Verified by your phone \u00b7 cannot be undone"
        } else {
            "Verified by your phone"
        }
    }

    test("FullyReversible shows simple verification") {
        verificationLine(Reversibility.FullyReversible) shouldBe "Verified by your phone"
    }

    test("PartiallyReversible shows simple verification") {
        verificationLine(Reversibility.PartiallyReversible) shouldBe "Verified by your phone"
    }

    test("OneShot shows cannot-be-undone verification") {
        verificationLine(Reversibility.OneShot) shouldContain "cannot be undone"
    }

    test("Irreversible shows cannot-be-undone verification") {
        verificationLine(Reversibility.Irreversible) shouldContain "cannot be undone"
    }

    // -- targetForKind: never returns raw numbers or empty for display kinds --

    test("targetForKind returns non-empty for all registered kinds") {
        // read_contacts intentionally returns "" because its template
        // ("Access your contacts?") doesn't use a target placeholder
        val kindsWithTarget = allKinds.filter { it != "read_contacts" }
        kindsWithTarget.forEach { kind ->
            ActionTemplates.targetForKind(kind).shouldNotBeEmpty()
        }
    }

    test("targetForKind for unknown kind returns readable phrase") {
        ActionTemplates.targetForKind("launch_rocket") shouldBe "launch rocket"
    }

    // -- infinitivePhrase --

    test("infinitivePhrase returns non-empty for all registered kinds") {
        allKinds.forEach { kind ->
            ActionTemplates.infinitivePhrase(kind).shouldNotBeEmpty()
        }
    }

    test("infinitivePhrase never returns past-tense forms") {
        allKinds.forEach { kind ->
            val firstWord = ActionTemplates.infinitivePhrase(kind).substringBefore(' ')
            firstWord shouldNotBe "sent"
            firstWord shouldNotBe "posted"
            firstWord shouldNotBe "scheduled"
            firstWord shouldNotBe "deleted"
            firstWord shouldNotBe "made"
            firstWord shouldNotBe "saved"
            firstWord shouldNotBe "opened"
            firstWord shouldNotBe "created"
            firstWord shouldNotBe "signed"
            firstWord shouldNotBe "accessed"
        }
    }

    test("infinitivePhrase concrete mappings") {
        ActionTemplates.infinitivePhrase("send_email") shouldBe "send an email"
        ActionTemplates.infinitivePhrase("post_social") shouldBe "post to social media"
        ActionTemplates.infinitivePhrase("read_file") shouldBe "read a file"
        ActionTemplates.infinitivePhrase("delete_file") shouldBe "delete a file"
    }

    test("targetForKind never returns the same word as the template verb") {
        ActionTemplates.targetForKind("post_social") shouldBe "social media"
        ActionTemplates.targetForKind("send_email") shouldBe "an email"
        ActionTemplates.targetForKind("read_file") shouldBe "a file"
    }

    test("targetForKind never returns a raw number") {
        allKinds.forEach { kind ->
            val target = ActionTemplates.targetForKind(kind)
            (target.toIntOrNull() == null || target.isEmpty()) shouldBe true
        }
    }
})

private fun makeSnapshot(gamma: Double) = GovernanceSnapshot(
    gamma = gamma,
    recentEntropyAverage = 0.1,
    recentDivergenceAverage = 0.05,
    recentOutcomes = OutcomeCounts(pass = 10, hold = 2, veto = 0, window = 24.hours),
    referenceEnvelopeDescription = "test",
    warmupComplete = true,
    timestamp = kotlinx.datetime.Clock.System.now(),
)
