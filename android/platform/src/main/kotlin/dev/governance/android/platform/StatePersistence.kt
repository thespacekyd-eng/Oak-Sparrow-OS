package dev.governance.android.platform

import android.content.Context
import dev.governance.core.GovernanceState
import dev.governance.core.ReferenceEnvelope
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the current [GovernanceState] to disk and restores it on boot.
 *
 * The state file is a single JSON file under `context.filesDir/state/`.
 * It is overwritten atomically on every kernel update (write-to-temp, rename).
 *
 * ## Corruption recovery
 *
 * If the state file is corrupted or unreadable on boot, returns a fresh
 * defensive-prior state and logs a warning via the provided callback.
 * This avoids bricking the governance system but does lose calibration
 * history. The audit log remains intact for forensic review.
 */
class StatePersistence(private val stateDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "state"))

    private val stateFile = File(stateDir, STATE_FILE_NAME)

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        if (!stateDir.exists()) stateDir.mkdirs()
    }

    /**
     * Save the current governance state. Uses atomic write (temp file + rename)
     * to prevent corruption on process death.
     */
    fun save(state: GovernanceState) {
        val serialized = json.encodeToString(GovernanceState.serializer(), state)
        val tempFile = File(stateDir, "$STATE_FILE_NAME.tmp")
        tempFile.writeText(serialized, Charsets.UTF_8)
        tempFile.renameTo(stateFile)
    }

    /**
     * Load the persisted governance state, or null if no state file exists.
     * Returns null (not a default state) so the caller can decide how to
     * handle first-boot vs corruption.
     */
    fun load(): GovernanceState? {
        if (!stateFile.exists()) return null
        return try {
            val text = stateFile.readText(Charsets.UTF_8)
            json.decodeFromString(GovernanceState.serializer(), text)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val STATE_FILE_NAME = "governance_state.json"

        /**
         * Fresh defensive-prior state for first boot or corruption recovery.
         * Matches the kernel's test fixtures.
         */
        /**
         * @param debugMode when true, starts with gamma=0.3 so negative latency
         *   is reachable after just a few benign decisions. Production starts at 0.85.
         */
        fun freshDefensiveState(debugMode: Boolean = false): GovernanceState = GovernanceState(
            gamma = if (debugMode) 0.30 else 0.85,
            referenceEnvelope = ReferenceEnvelope(
                center = listOf(0.7, 0.2, 0.1),
                radius = 2.0,
                description = "Default defensive-prior envelope (wide radius)",
            ),
            recentHistory = emptyList(),
            decisionsObserved = 0,
            timestamp = Clock.System.now(),
        )
    }
}
