package dev.governance.android.app.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import dev.governance.android.app.HoldConfirmationDialog
import dev.governance.android.app.ui.PreviewKernelState
import dev.governance.android.app.ui.screens.*
import dev.governance.android.app.ui.components.VerifiedBadge
import dev.governance.core.Outcome
import dev.governance.core.Reversibility
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi screenshot tests for all Phase 2B screens.
 * Each screen is rendered at Pixel 5 size (393x851 dp) in light and dark mode.
 *
 * Run: ./gradlew :android-app:recordPaparazziDebug
 * Verify: ./gradlew :android-app:verifyPaparazziDebug
 */
class ScreenshotTests {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
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

    // -- OnboardingPage (individual pages, avoids ActivityResultRegistry requirement) --

    @Test
    fun `07a_onboarding_intro_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                OnboardingPage(
                    icon = Icons.Filled.Star,
                    title = "Agent governance",
                    body = "Oak & Sparrow reviews every action an AI agent proposes on your behalf before it executes.",
                    buttonText = "Next",
                    onAction = {},
                )
            }
        }
    }

    @Test
    fun `07a_onboarding_intro_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                OnboardingPage(
                    icon = Icons.Filled.Star,
                    title = "Agent governance",
                    body = "Oak & Sparrow reviews every action an AI agent proposes on your behalf before it executes.",
                    buttonText = "Next",
                    onAction = {},
                )
            }
        }
    }

    @Test
    fun `07b_onboarding_accessibility_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                OnboardingPage(
                    icon = Icons.Filled.Person,
                    title = "Accessibility access",
                    body = "To verify that agent actions actually completed as reported, we need accessibility service access.",
                    buttonText = "Open Settings",
                    onAction = {},
                )
            }
        }
    }

    @Test
    fun `07b_onboarding_accessibility_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                OnboardingPage(
                    icon = Icons.Filled.Person,
                    title = "Accessibility access",
                    body = "To verify that agent actions actually completed as reported, we need accessibility service access.",
                    buttonText = "Open Settings",
                    onAction = {},
                )
            }
        }
    }

    @Test
    fun `07c_onboarding_notifications_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                OnboardingPage(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    body = "We need notification permission to show you when agent actions need your approval.",
                    buttonText = "Grant",
                    onAction = {},
                )
            }
        }
    }

    @Test
    fun `07c_onboarding_notifications_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                OnboardingPage(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    body = "We need notification permission to show you when agent actions need your approval.",
                    buttonText = "Grant",
                    onAction = {},
                )
            }
        }
    }

    // -- HoldConfirmationDialog --

    @Test
    fun `08_auth_dialog_reversible_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            val decision = PreviewKernelState.makeDecision(
                kind = "read_file", outcome = Outcome.HOLD,
                reversibility = Reversibility.FullyReversible, seq = 0,
            )
            HoldConfirmationDialog(decision = decision, onApprove = {}, onSkip = {})
        }
    }

    @Test
    fun `08_auth_dialog_reversible_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val decision = PreviewKernelState.makeDecision(
                kind = "read_file", outcome = Outcome.HOLD,
                reversibility = Reversibility.FullyReversible, seq = 0,
            )
            HoldConfirmationDialog(decision = decision, onApprove = {}, onSkip = {})
        }
    }

    @Test
    fun `09_auth_dialog_oneshot_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            val decision = PreviewKernelState.makeDecision(
                kind = "send_email", outcome = Outcome.HOLD,
                reversibility = Reversibility.OneShot, seq = 0,
            )
            HoldConfirmationDialog(decision = decision, onApprove = {}, onSkip = {})
        }
    }

    @Test
    fun `09_auth_dialog_oneshot_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val decision = PreviewKernelState.makeDecision(
                kind = "send_email", outcome = Outcome.HOLD,
                reversibility = Reversibility.OneShot, seq = 0,
            )
            HoldConfirmationDialog(decision = decision, onApprove = {}, onSkip = {})
        }
    }

    @Test
    fun `10_auth_dialog_irreversible_light`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme()) {
            val decision = PreviewKernelState.makeDecision(
                kind = "post_social", outcome = Outcome.HOLD,
                reversibility = Reversibility.Irreversible, seq = 0,
            )
            HoldConfirmationDialog(decision = decision, onApprove = {}, onSkip = {})
        }
    }

    @Test
    fun `10_auth_dialog_irreversible_dark`() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val decision = PreviewKernelState.makeDecision(
                kind = "post_social", outcome = Outcome.HOLD,
                reversibility = Reversibility.Irreversible, seq = 0,
            )
            HoldConfirmationDialog(decision = decision, onApprove = {}, onSkip = {})
        }
    }
}
