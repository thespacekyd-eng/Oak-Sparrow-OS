package dev.governance.core

/**
 * Top-level entry point for the governance kernel. Governs an external agent's
 * proposed actions through a state-estimator-and-gate architecture.
 *
 * The kernel does not produce actions — an external agent does, and the kernel
 * decides whether each proposed action passes, holds, or is vetoed.
 *
 * State is managed externally: callers pass [GovernanceState] in and receive
 * updated states back. This makes the kernel thread-safe and testable.
 */
interface GovernanceKernel {

    /**
     * Evaluate a proposed action against the current governance state.
     * Returns a signed [GateDecision] with outcome PASS, HOLD, or VETO.
     */
    fun decide(state: GovernanceState, proposed: ProposedAction): GateDecision

    /**
     * Update governance state after a decision outcome resolves. This is the
     * kernel's only learning mechanism — γ and the reference envelope adjust
     * based on [outcome].
     */
    fun resolve(
        state: GovernanceState,
        decision: GateDecision,
        outcome: ResolvedOutcome,
    ): GovernanceState

    /**
     * Produce a read-only [GovernanceSnapshot] for cooperative agents.
     * Agents can consult this to self-modulate before proposing actions.
     */
    fun snapshot(state: GovernanceState): GovernanceSnapshot
}
