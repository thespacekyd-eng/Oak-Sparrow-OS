package dev.governance.android.app.agent

/**
 * Strips personally identifiable information from user instructions
 * before they are sent to a cloud LLM API. Runs on-device, before
 * any network egress.
 *
 * Design: conservative replacement. We strip data patterns (phone
 * numbers, emails, SSNs, credit cards) but preserve semantic intent
 * words like contact names ("mom", "John") because the cloud LLM
 * needs them to build a correct plan. The actual phone number behind
 * "mom" never leaves the device — it's resolved locally by the
 * ActionDispatcher after the plan comes back.
 */
object PiiSanitizer {

    private val PHONE = Regex("""\+?\d[\d\s\-().]{6,}\d""")
    private val EMAIL = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""")
    private val SSN = Regex("""\b\d{3}-\d{2}-\d{4}\b""")
    private val CREDIT_CARD = Regex("""\b\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{4}\b""")
    private val IP_ADDRESS = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
    // US street addresses (e.g., "123 Main St", "456 Oak Ave Apt 7")
    private val ADDRESS = Regex("""\b\d{1,5}\s+[A-Z][a-zA-Z]+\s+(St|Ave|Blvd|Dr|Ln|Rd|Way|Ct|Pl|Cir|Pkwy|Terr)\.?\b""", RegexOption.IGNORE_CASE)
    // Date of birth patterns (e.g., "01/15/1990", "1990-01-15")
    private val DOB = Regex("""\b(0[1-9]|1[0-2])[/\-](0[1-9]|[12]\d|3[01])[/\-](19|20)\d{2}\b|\b(19|20)\d{2}[/\-](0[1-9]|1[0-2])[/\-](0[1-9]|[12]\d|3[01])\b""")
    // US driver's license (common formats: 1-13 alphanumeric)
    private val DRIVERS_LICENSE = Regex("""\b[A-Z]\d{7,12}\b""")

    /**
     * Returns the instruction with PII patterns replaced by safe
     * placeholders. The mapping is returned so callers can substitute
     * back after the cloud response arrives.
     */
    fun sanitize(instruction: String): SanitizeResult {
        var text = instruction
        val mappings = mutableMapOf<String, String>()

        text = SSN.replace(text) { m ->
            val key = "[SSN_${mappings.size}]"
            mappings[key] = m.value
            key
        }
        text = CREDIT_CARD.replace(text) { m ->
            val key = "[CARD_${mappings.size}]"
            mappings[key] = m.value
            key
        }
        text = EMAIL.replace(text) { m ->
            val key = "[EMAIL_${mappings.size}]"
            mappings[key] = m.value
            key
        }
        text = PHONE.replace(text) { m ->
            val key = "[PHONE_${mappings.size}]"
            mappings[key] = m.value
            key
        }
        text = IP_ADDRESS.replace(text) { m ->
            val key = "[IP_${mappings.size}]"
            mappings[key] = m.value
            key
        }
        text = ADDRESS.replace(text) { m ->
            val key = "[ADDR_${mappings.size}]"
            mappings[key] = m.value
            key
        }
        text = DOB.replace(text) { m ->
            val key = "[DOB_${mappings.size}]"
            mappings[key] = m.value
            key
        }
        text = DRIVERS_LICENSE.replace(text) { m ->
            val key = "[DL_${mappings.size}]"
            mappings[key] = m.value
            key
        }

        return SanitizeResult(text, mappings)
    }

    /** Restores placeholders in a response string with original values. */
    fun restore(text: String, mappings: Map<String, String>): String {
        var result = text
        for ((placeholder, original) in mappings) {
            result = result.replace(placeholder, original)
        }
        return result
    }

    data class SanitizeResult(
        val sanitized: String,
        val mappings: Map<String, String>,
    )
}
