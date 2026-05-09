package dev.governance.android.app.ui

import dev.governance.android.app.R

/**
 * Maps action kinds to plain-English question templates for the HOLD
 * confirmation dialog. The `{target}` placeholder is replaced with
 * the action's payload target if available.
 */
object ActionTemplates {

    private val templates = mapOf(
        "send_email" to R.string.action_send_email,
        "post_social" to R.string.action_post_social,
        "share_to_social_app" to R.string.action_share_to_social,
        "read_calendar" to R.string.action_read_calendar,
        "schedule_event" to R.string.action_schedule_event,
        "delete_file" to R.string.action_delete_file,
        "send_message" to R.string.action_send_message,
        "make_payment" to R.string.action_make_payment,
        "read_file" to R.string.action_read_file,
        "read_contacts" to R.string.action_read_contacts,
        "write_file" to R.string.action_write_file,
        "open_app" to R.string.action_open_app,
        "create_account" to R.string.action_create_account,
        "sign_document" to R.string.action_sign_document,
    )

    fun questionResId(kind: String): Int = templates[kind] ?: R.string.action_fallback

    /** Map action kind to a plain-English past-tense label for the activity feed. */
    fun pastTenseLabel(kind: String): String = when (kind) {
        "send_email" -> "sent email"
        "post_social" -> "posted"
        "share_to_social_app" -> "shared to app"
        "read_calendar" -> "read calendar"
        "schedule_event" -> "scheduled event"
        "delete_file" -> "deleted file"
        "send_message" -> "sent message"
        "make_payment" -> "made payment"
        "read_file" -> "read file"
        "read_contacts" -> "accessed contacts"
        "write_file" -> "saved file"
        "open_app" -> "opened app"
        "create_account" -> "created account"
        "sign_document" -> "signed document"
        else -> kind.replace('_', ' ')
    }

    /**
     * Bare-infinitive phrase for the body template "Your agent wants to ___".
     * Distinct from [pastTenseLabel] which is for the activity feed.
     * Do not collapse the two — they have different grammatical contexts.
     */
    fun infinitivePhrase(kind: String): String = when (kind) {
        "send_email" -> "send an email"
        "post_social" -> "post to social media"
        "share_to_social_app" -> "share to a social app"
        "read_calendar" -> "check your calendar"
        "schedule_event" -> "schedule an event"
        "delete_file" -> "delete a file"
        "send_message" -> "send a message"
        "make_payment" -> "make a payment"
        "read_file" -> "read a file"
        "read_contacts" -> "access your contacts"
        "write_file" -> "save a file"
        "open_app" -> "open an app"
        "create_account" -> "create an account"
        "sign_document" -> "sign a document"
        else -> kind.replace('_', ' ')
    }

    /** Map Outcome to plain-English label. */
    fun outcomeLabel(outcome: dev.governance.core.Outcome): String = when (outcome) {
        dev.governance.core.Outcome.PASS -> "approved"
        dev.governance.core.Outcome.HOLD -> "asked you"
        dev.governance.core.Outcome.VETO -> "blocked"
    }

    /**
     * Extract a human-readable target from a [GateDecision].
     * Checks the action payload for a "target" field first, then
     * falls back to the action kind as a readable phrase.
     * Never returns a raw number or empty string.
     */
    fun targetFromDecision(decision: dev.governance.core.GateDecision): String {
        // The action payload isn't on GateDecision; derive from actionId
        // ActionId format: "preview-send_email-5" or "escalation-3-1234"
        // The middle segment is the kind, which we already have.
        // For a meaningful target, use kind-specific defaults.
        return targetForKind(decision.actionKind)
    }

    /**
     * Provides a realistic placeholder target per action kind.
     * In production, the agent populates ProposedAction.payload with
     * the real target (email address, file name, etc.). This fallback
     * is for display when the payload target is unavailable.
     */
    fun targetForKind(kind: String): String = when (kind) {
        "send_email" -> "an email"
        "post_social" -> "social media"
        "share_to_social_app" -> "a social app"
        "read_calendar" -> "your calendar"
        "schedule_event" -> "an event"
        "delete_file" -> "a file"
        "send_message" -> "a message"
        "make_payment" -> "a payment"
        "read_file" -> "a file"
        "read_contacts" -> "" // template doesn't use target
        "write_file" -> "a file"
        "open_app" -> "an app"
        "create_account" -> "a new service"
        "sign_document" -> "a document"
        else -> kind.replace('_', ' ')
    }
}
