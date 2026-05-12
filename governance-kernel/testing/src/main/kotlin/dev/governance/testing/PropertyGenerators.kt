package dev.governance.testing

import dev.governance.core.*
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Kotest Arb generators for property-based testing of governance types. */
object PropertyGenerators {

    /** γ values in the valid range [0, 1]. */
    fun arbGamma(): Arb<Double> = Arb.double(0.0, 1.0)

    /** Arbitrary reversibility tier. */
    fun arbReversibility(): Arb<Reversibility> = Arb.enum<Reversibility>()

    /** Arbitrary outcome. */
    fun arbOutcome(): Arb<Outcome> = Arb.enum<Outcome>()

    /** Arbitrary resolved outcome. */
    fun arbResolvedOutcome(): Arb<ResolvedOutcome> = Arb.choice(
        Arb.constant(ResolvedOutcome.BenignSuccess),
        Arb.constant(ResolvedOutcome.HarmlessFailure),
        Arb.string(5..20).map { ResolvedOutcome.Flagged(it) },
    )

    /** Arbitrary action ID. */
    fun arbActionId(): Arb<ActionId> = Arb.string(8..16).map { ActionId(it) }

    /** Arbitrary action kind from a realistic set. */
    fun arbActionKind(): Arb<String> = Arb.element(
        "read_file", "write_file", "delete_file",
        "send_message", "send_email", "make_payment",
        "query_database", "open_app", "read_contacts",
        "post_comment", "create_account", "sign_document",
    )

    /** Arbitrary proposed action. */
    fun arbProposedAction(): Arb<ProposedAction> = Arb.bind(
        arbActionId(),
        arbActionKind(),
        arbReversibility(),
    ) { id, kind, rev ->
        ProposedAction(id = id, kind = kind, reversibility = rev)
    }

    /** Arbitrary reference envelope with valid dimensions. */
    fun arbReferenceEnvelope(): Arb<ReferenceEnvelope> = Arb.bind(
        Arb.list(Arb.double(0.0, 1.0), 3..3),
        Arb.double(0.1, 2.0),
    ) { center, radius ->
        ReferenceEnvelope(center = center, radius = radius, description = "test envelope")
    }

    /** Arbitrary history entry. */
    fun arbHistoryEntry(): Arb<HistoryEntry> = Arb.bind(
        arbActionId(),
        arbActionKind(),
        arbOutcome(),
        arbGamma(),
    ) { id, kind, outcome, gamma ->
        HistoryEntry(
            actionId = id,
            kind = kind,
            outcome = outcome,
            gamma = gamma,
            timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
    }

    /** Arbitrary governance state with bounded history. */
    fun arbGovernanceState(maxHistory: Int = 20): Arb<GovernanceState> = Arb.bind(
        arbGamma(),
        arbReferenceEnvelope(),
        Arb.list(arbHistoryEntry(), 0..maxHistory),
    ) { gamma, envelope, history ->
        GovernanceState(
            gamma = gamma,
            referenceEnvelope = envelope,
            recentHistory = history,
            decisionsObserved = history.size.toLong(),
            timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
    }

    /** Sequence of resolved outcomes for calibration testing. */
    fun arbResolvedOutcomeSequence(length: Int): Arb<List<ResolvedOutcome>> =
        Arb.list(arbResolvedOutcome(), length..length)
}
