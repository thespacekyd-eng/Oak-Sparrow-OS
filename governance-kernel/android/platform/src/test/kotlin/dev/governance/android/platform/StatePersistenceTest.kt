package dev.governance.android.platform

import dev.governance.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.Instant
import java.io.File
import java.nio.file.Files

/**
 * Tests for [StatePersistence] — state save/load and corruption recovery.
 * Runs on JVM using a temp directory.
 */
class StatePersistenceTest : FunSpec({

    lateinit var tempDir: File

    beforeTest {
        tempDir = Files.createTempDirectory("state-test").toFile()
    }

    afterTest {
        tempDir.deleteRecursively()
    }

    test("save and load round-trips GovernanceState") {
        val persistence = StatePersistence(tempDir)
        val state = GovernanceState(
            gamma = 0.42,
            referenceEnvelope = ReferenceEnvelope(
                center = listOf(0.6, 0.3, 0.1),
                radius = 1.5,
                description = "test",
            ),
            recentHistory = emptyList(),
            decisionsObserved = 99,
            timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )

        persistence.save(state)
        val loaded = persistence.load()
        loaded shouldBe state
    }

    test("load returns null when no state file exists") {
        val persistence = StatePersistence(tempDir)
        persistence.load() shouldBe null
    }

    test("load returns null on corrupted state file") {
        val persistence = StatePersistence(tempDir)
        File(tempDir, StatePersistence.STATE_FILE_NAME).writeText("{{invalid json")
        persistence.load() shouldBe null
    }

    test("fresh defensive state has valid gamma") {
        val fresh = StatePersistence.freshDefensiveState()
        fresh.gamma shouldBe 0.85
        fresh.decisionsObserved shouldBe 0
        fresh.recentHistory shouldBe emptyList()
    }
})
