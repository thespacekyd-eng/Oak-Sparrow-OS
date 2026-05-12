package dev.governance.android.app.agent

import dev.governance.core.Reversibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpeculationLogTest : FunSpec({

    fun step(kind: String, target: String? = null) = PlannedStep(
        kind = kind,
        target = target,
        rationale = "test",
        reversibility = Reversibility.FullyReversible,
    )

    test("recordStarted appends a Speculating entry and returns its id") {
        val log = SpeculationLog()
        val id = log.recordStarted(step("open_app", "instagram"))

        log.entries.size shouldBe 1
        log.entries[0].id shouldBe id
        log.entries[0].state shouldBe SpeculationLog.State.Speculating
        log.entries[0].kind shouldBe "open_app"
        log.entries[0].target shouldBe "instagram"
        log.entries[0].auditId shouldBe null
        log.entries[0].reason shouldBe null
        log.entries[0].resolvedAt shouldBe null
    }

    test("recordCommitted updates state and auditId, sets resolvedAt") {
        val log = SpeculationLog()
        val id = log.recordStarted(step("read_calendar"))
        log.recordCommitted(id, "audit-abc123")

        val entry = log.entries[0]
        entry.state shouldBe SpeculationLog.State.Committed
        entry.auditId shouldBe "audit-abc123"
        (entry.resolvedAt != null) shouldBe true
    }

    test("recordRolledBack updates state and reason, sets resolvedAt") {
        val log = SpeculationLog()
        val id = log.recordStarted(step("open_app", "chrome"))
        log.recordRolledBack(id, "user declined")

        val entry = log.entries[0]
        entry.state shouldBe SpeculationLog.State.RolledBack
        entry.reason shouldBe "user declined"
        (entry.resolvedAt != null) shouldBe true
    }

    test("ids are monotonically increasing") {
        val log = SpeculationLog()
        val a = log.recordStarted(step("open_app", "a"))
        val b = log.recordStarted(step("open_app", "b"))
        val c = log.recordStarted(step("open_app", "c"))
        (b > a) shouldBe true
        (c > b) shouldBe true
    }

    test("count returns the number of entries in a given state") {
        val log = SpeculationLog()
        val id1 = log.recordStarted(step("open_app", "a"))
        val id2 = log.recordStarted(step("open_app", "b"))
        val id3 = log.recordStarted(step("open_app", "c"))

        log.recordCommitted(id1, "audit-1")
        log.recordRolledBack(id2, "veto")
        // id3 stays Speculating

        log.count(SpeculationLog.State.Committed) shouldBe 1
        log.count(SpeculationLog.State.RolledBack) shouldBe 1
        log.count(SpeculationLog.State.Speculating) shouldBe 1
    }

    test("updating a non-existent id is a no-op") {
        val log = SpeculationLog()
        log.recordStarted(step("open_app", "a"))
        log.recordCommitted(id = 99_999L, auditId = "fake")
        // Should not crash; existing entry stays Speculating.
        log.entries.size shouldBe 1
        log.entries[0].state shouldBe SpeculationLog.State.Speculating
    }
})
