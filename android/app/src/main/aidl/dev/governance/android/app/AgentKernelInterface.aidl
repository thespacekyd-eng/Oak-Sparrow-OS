package dev.governance.android.app;

import dev.governance.android.platform.parcel.ProposedActionParcel;
import dev.governance.android.platform.parcel.GateDecisionParcel;
import dev.governance.android.platform.parcel.GovernanceSnapshotParcel;
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel;

/**
 * Binder interface for agent-to-kernel communication.
 *
 * The agent process binds to GovernanceKernelService and uses this
 * interface to propose actions, resolve outcomes, and read snapshots.
 *
 * All methods are synchronous from the caller's perspective. The kernel
 * service serializes access to GovernanceState internally.
 */
interface AgentKernelInterface {
    /** Evaluate a proposed action. Returns a signed GateDecision. */
    GateDecisionParcel decide(in ProposedActionParcel action);

    /**
     * Resolve a previously decided action by its audit ID.
     * The kernel updates gamma and the reference envelope based on
     * the outcome.
     */
    void resolve(String decisionAuditId, in ResolvedOutcomeParcel outcome);

    /** Read-only snapshot of current governance state for agent self-modulation. */
    GovernanceSnapshotParcel snapshot();
}
