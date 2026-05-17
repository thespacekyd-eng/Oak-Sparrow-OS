package dev.governance.metrics

import dev.governance.attestation.EphemeralKeyProvider
import dev.governance.plan.DefaultPlanDecisionSigner
import java.nio.file.Paths

fun main(args: Array<String>) {
    val logPath = args.getOrNull(0)

    if (logPath == null) {
        GovernanceMetricsSelfCheck.runAll()
        val metrics = GovernanceMetrics.from(GovernanceMetricsSelfCheck.demoDecisionLog())
        println(GovernanceMetricsCli.render(metrics))
        return
    }

    // TODO(plan-metrics): External logs are read with verifySignatures=false because
    // the CLI does not yet accept a trusted public key. The EphemeralKeyProvider used
    // here generates a fresh keypair on each invocation, so signer.verify(...) would
    // reject every record signed by a different signer. Two paths to fix:
    //   (a) accept a --pubkey hex arg and build a verify-only PlanDecisionSigner
    //   (b) extend PlanDecisionSigner to verify against SignedDecision.publicKey
    //       (matches plan-governance's PlanDecisionLog.verifyAll behavior)
    val signer = DefaultPlanDecisionSigner(EphemeralKeyProvider())
    val adapter = PlanGovernanceAdapter(signer = signer, verifySignatures = false)
    val log = adapter.read(Paths.get(logPath))
    val metrics = GovernanceMetrics.from(log)
    println(GovernanceMetricsCli.render(metrics))
}