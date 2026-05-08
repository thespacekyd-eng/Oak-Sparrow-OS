package dev.governance.android.app.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import dev.governance.android.app.ui.PreviewKernelState
import dev.governance.android.app.ui.screens.*
import dev.governance.android.app.ui.components.VerifiedBadge
import dev.governance.core.Outcome
import dev.governance.core.Reversibility
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi screenshot tests for all Phase 2B screens.
 * Each screen is rendered at 380x800 in light and dark mode.
 *
 * Run: ./gradlew :android-app:recordPaparazziDebug
 * Verify: ./gradlew :android-app:verifyPaparazziDebug
 */
class ScreenshotTests {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 380,
            screenHeight = 800,
        ),
    )

    // -- HomeScreen --

    @Test
    fun `01_home_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                HomeScreen(
                    snapshot = PreviewKernelState.snapshot,
                    recentDecisions = PreviewKernelState.recentDecisions,
                    observationCount = 47,
                    errorCount = 0,
                    onDecisionTap = {},
                    onSeeDetails = {},
                )
            }
        }
    }

    @Test
    fun `01_home_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                HomeScreen(
                    snapshot = PreviewKernelState.snapshot,
                    recentDecisions = PreviewKernelState.recentDecisions,
                    observationCount = 47,
                    errorCount = 0,
                    onDecisionTap = {},
                    onSeeDetails = {},
                )
            }
        }
    }

    // -- RecentDecisionsScreen --

    @Test
    fun `02_recent_decisions_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                RecentDecisionsScreen(records = PreviewKernelState.auditRecords)
            }
        }
    }

    @Test
    fun `02_recent_decisions_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                RecentDecisionsScreen(records = PreviewKernelState.auditRecords)
            }
        }
    }

    // -- AppPermissionsScreen --

    @Test
    fun `03_app_permissions_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                AppPermissionsScreen(
                    apps = listOf(
                        AppCapability("com.google.android.gm", "Gmail"),
                        AppCapability("com.instagram.android", "Instagram",
                            level = AppPermissionLevel.FULL),
                    ),
                    onUpdate = {},
                )
            }
        }
    }

    @Test
    fun `03_app_permissions_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                AppPermissionsScreen(
                    apps = listOf(
                        AppCapability("com.google.android.gm", "Gmail"),
                        AppCapability("com.instagram.android", "Instagram",
                            level = AppPermissionLevel.FULL),
                    ),
                    onUpdate = {},
                )
            }
        }
    }

    // -- TechnicalDetailScreen --

    @Test
    fun `04_technical_detail_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                TechnicalDetailScreen(
                    snapshot = PreviewKernelState.snapshot,
                    records = PreviewKernelState.auditRecords,
                    systemEvents = PreviewKernelState.systemEvents.map { it.event },
                    chainVerified = true,
                    chainProblemTime = null,
                    expandSystemEvents = true,
                )
            }
        }
    }

    @Test
    fun `04_technical_detail_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                TechnicalDetailScreen(
                    snapshot = PreviewKernelState.snapshot,
                    records = PreviewKernelState.auditRecords,
                    systemEvents = PreviewKernelState.systemEvents.map { it.event },
                    chainVerified = true,
                    chainProblemTime = null,
                    expandSystemEvents = true,
                )
            }
        }
    }

    // -- VerificationFailureScreen --

    @Test
    fun `05_verification_failure_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                VerificationFailureScreen(onDiagnose = {})
            }
        }
    }

    @Test
    fun `05_verification_failure_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                VerificationFailureScreen(onDiagnose = {})
            }
        }
    }

    // -- VerifiedBadge component --

    @Test
    fun `06_verified_badge_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            VerifiedBadge()
        }
    }

    @Test
    fun `06_verified_badge_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            VerifiedBadge()
        }
    }
}
