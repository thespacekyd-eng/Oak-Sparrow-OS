package dev.governance.core

import kotlinx.serialization.Serializable

/** Content-addressed unique identifier for a proposed action. */
@JvmInline
@Serializable
value class ActionId(val value: String)

/** Content-addressed unique identifier for an audit record (SHA-256 of canonical decision JSON). */
@JvmInline
@Serializable
value class AuditId(val value: String)

/**
 * How reversible a proposed action is. The composite gate biases toward
 * HOLD proportionally to irreversibility — the same borderline soft state
 * may produce PASS for [FullyReversible] and HOLD for [Irreversible].
 */
@Serializable
enum class Reversibility {
    /** Action can be undone with no residual cost (e.g., reading a file). */
    FullyReversible,
    /** Recovery path exists but at some cost (e.g., drafting an email). */
    PartiallyReversible,
    /** Commits but doesn't compound (e.g., sending a single message). */
    OneShot,
    /** Cannot be undone and may compound (e.g., payment, public post). */
    Irreversible,
}

/** The gate's three possible decisions. No additional values in Phase 1. */
@Serializable
enum class Outcome { PASS, HOLD, VETO }

/**
 * How a previously decided action actually resolved after execution (or non-execution).
 * This is the only feedback mechanism the kernel uses to learn.
 */
@Serializable
sealed interface ResolvedOutcome {
    /** Action executed and completed without causing any flagged harm. */
    @Serializable
    data object BenignSuccess : ResolvedOutcome

    /** Action was held/vetoed or failed, but nothing harmful occurred. */
    @Serializable
    data object HarmlessFailure : ResolvedOutcome

    /** Action caused or revealed a problem requiring investigation. */
    @Serializable
    data class Flagged(val reason: String) : ResolvedOutcome
}
