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

    /** Map Outcome to plain-English label. */
    fun outcomeLabel(outcome: dev.governance.core.Outcome): String = when (outcome) {
        dev.governance.core.Outcome.PASS -> "approved"
        dev.governance.core.Outcome.HOLD -> "asked you"
        dev.governance.core.Outcome.VETO -> "blocked"
    }
}
