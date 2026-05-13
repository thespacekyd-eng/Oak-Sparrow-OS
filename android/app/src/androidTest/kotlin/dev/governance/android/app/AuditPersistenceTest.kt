package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import dev.governance.android.platform.AndroidJsonlAuditWriter
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel
import dev.governance.core.*
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/**
 * Tests that audit records are written durably and can be read back
 * after an unbind/rebind cycle.
 *
 * Note: The foreground service survives unbind (START_STICKY), so the
 * unbind/rebind here tests binder reconnection, not process restart.
 * Process-death recovery is verified manually via EMULATOR_RUNBOOK check #10.
 */
class AuditPersistenceTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun tenDecisionsSurviveUnbindRebind() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)

        val binder = serviceRule.bindService(intent)
        val kernel = AgentKernelInterface.Stub.asInterface(binder)

        val testPrefix = "audit-test-${System.nanoTime()}-"

        // Submit 10 decisions with unique IDs and resolve each
        val submittedIds = mutableListOf<String>()
        repeat(10) { i ->
            val actionId = "$testPrefix$i"
            submittedIds.add(actionId)
            val action = ProposedAction(
                id = ActionId(actionId),
                kind = "read_file",
                reversibility = Reversibility.FullyReversible,
            )
            val decision = kernel.decide(ProposedActionParcel.from(action)).toKernel()
            kernel.resolve(
                decision.auditId.value,
                ResolvedOutcomeParcel.from(ResolvedOutcome.BenignSuccess),
            )
        }

        // Unbind and rebind to force any pending I/O
        serviceRule.unbindService()
        serviceRule.bindService(intent)

        // Read audit log directly and filter to our test records
        val auditWriter = AndroidJsonlAuditWriter(context)
        val allRecords = auditWriter.readAll()
        val ourRecords = allRecords.filter {
            it.proposedAction.id.value.startsWith(testPrefix)
        }

        ourRecords shouldHaveAtLeastSize 10

        // All submitted action IDs should be present in the audit
        val recordedIds = ourRecords.map { it.proposedAction.id.value }.toSet()
        submittedIds.forEach { id ->
            recordedIds.contains(id) shouldBe true
        }
    }
}
