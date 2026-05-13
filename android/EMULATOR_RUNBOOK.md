# Oak & Sparrow — Emulator Integration Runbook

## Automated testing (per-change gate)

Most behavioral checks from the original runbook are now automated
as instrumented UI tests. Run them against a connected emulator:

```bash
./gradlew :android-app:uiCheck
```

This runs all `connectedDebugAndroidTest` tests including:

| Runbook check | Automated by | Test class |
|---|---|---|
| 1. Onboarding flow | 3 tests | `OnboardingFlowTest` |
| 2. Accessibility service | 2 tests | `AccessibilityIntegrationTest` |
| 3. Foreground notification | 2 tests | `ForegroundServiceLifecycleTest` |
| 4. Kernel snapshot in HomeScreen | 2 tests | `HomeScreenContentTest` |
| 5. HOLD dialog | 6 tests | `AuthorizationDialogTest` |
| 6. Verification failure route | 2 UI + 3 in-process | `VerificationFailureRouteTest` |
| 7. Technical detail (indirect) | via `ServiceInstrumentationTest` | snapshot validity |
| 8. App permissions persistence | — | manual (DataStore) |
| 9. Audit log | 1 test | `AuditPersistenceTest` |
| 10. Process death recovery | 1 test | `ForegroundServiceLifecycleTest` |

Total: ~30 automated instrumented tests.

## When to walk this runbook manually

- Before tagging a release
- After major design changes (layout, theme, trusted display surfaces)
- Never after individual code changes — `uiCheck` is the per-change gate

## Manual checks (visual judgment only)

These checks require human eyes and can't be automated:

### M1. Visual design integrity

**What to check:** Open each screen and verify the design looks
intentional — Material 3 surfaces, brand accent colors on trust
indicators, the auth dialog's black card with monospace header
feels like a system surface rather than a stock dialog.

**Screens:** Home, Recent Decisions, App Permissions, Technical
Detail, Onboarding (3 pages), Auth Dialog (reversible and
irreversible), Verification Failure.

### M2. Auth dialog feel

**What to check:** The HOLD confirmation dialog should feel
distinct from content the agent could render. It should read as
"the operating system is asking me something" not "an app is
showing a popup." The black card on dim scrim, monospace header,
and button asymmetry (Skip text vs Approve filled) are the
distinguishing visual cues.

### M3. Notification interaction

**What to check:** Tap the persistent notification. It should
open the app to the home screen. The notification should be
present on the lock screen (priority LOW means it shows but
doesn't alert).

### M4. App permissions persistence

**What to check:** Change a permission (e.g., Gmail from Scoped
to Full), force-stop the app, reopen. The setting should persist.

## Prerequisites

- Android emulator running (API 34+, Google APIs, x86_64)
- Both APKs installed:
  ```bash
  adb install android/app/build/outputs/apk/debug/android-app-debug.apk
  adb install android/test-agent/build/outputs/apk/debug/android-test-agent-debug.apk
  ```

## Capturing evidence

After completing manual checks or running automated tests:

```bash
./gradlew :android-app:captureEmulatorArtifacts
```

This produces `android/app/build/emulator-evidence/` containing
test reports, screenshots, and audit log.
