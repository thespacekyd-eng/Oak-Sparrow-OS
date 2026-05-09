# Oak & Sparrow — Emulator Integration Runbook

## What this runbook is

This runbook is the **manual portion** of Phase 2D. The emulator
run itself is a hands-on step you do on your local machine —
Claude Code's sandbox cannot reach `adb`, so the project ships
the artifacts (APKs, instrumented tests, evidence-capture task)
and you exercise them.

**Workflow:**
1. Start your emulator (Android Studio > Device Manager > play).
2. `./gradlew :android-app:connectedAndroidTest` — runs the 14
   instrumented tests against the emulator.
3. Install both APKs (commands below).
4. Walk through the 10 checks below, recording results in
   `runbook-results.md`.
5. `./gradlew :android-app:captureEmulatorArtifacts` — bundles
   test reports, audit log, and your runbook results into
   `app/build/emulator-evidence/`.

If a check fails, the audit log and Technical Detail screen are
your two diagnostic tools.

## Prerequisites

- Android emulator running (API 34+, Google APIs, x86_64)
- Both APKs installed:
  ```bash
  adb install android/app/build/outputs/apk/debug/android-app-debug.apk
  adb install android/test-agent/build/outputs/apk/debug/android-test-agent-debug.apk
  ```

## Manual verification checks

### 1. Fresh install and onboarding

**Steps:** Open "Oak & Sparrow" from launcher.

**Expected:** Three-page onboarding: (1) agent governance explanation,
(2) accessibility access prompt with "Open Settings" button,
(3) notifications permission prompt.

**Pass:** All three pages render, buttons are tappable, completing
onboarding navigates to the home screen.

**Fail signal:** Crash on launch, blank screen, or onboarding
loop (re-prompts after completion).

### 2. Accessibility service enable

**Steps:** Tap "Open Settings" on onboarding page 2. In system
accessibility settings, find "Oak & Sparrow" and enable it.

**Expected:** The service enables without error. Returning to the
app reflects the enabled state.

**Fail signal:** Service not listed, toggle crashes, or the
`outcomeCallback` never fires (check system events in Technical
Detail).

### 3. Foreground notification

**Steps:** Complete onboarding. Lock the screen. Unlock.

**Expected:** Persistent notification: "Oak & Sparrow / Agent
governance is on" with a shield icon. Tapping opens MainActivity.

**Fail signal:** No notification, notification disappears on
screen-off, or `MissingForegroundServiceTypeException` in logcat.

### 4. Kernel snapshot in HomeScreen

**Steps:** Open the Sparrow Test Agent. Tap "Read calendar
(reversible)" three times. Switch to Oak & Sparrow home.

**Expected:** The home screen hero card updates — the "approved"
count increases. Recent activity shows the three new decisions.

**Fail signal:** Counts stay at zero, recent activity is empty,
or the home screen shows preview data instead of live state.

### 5. HOLD dialog

**Steps:** In the test agent, tap "Post photos (irreversible)".

**Expected:** The `AuthorizationActivity` launches as a dialog
overlay. Title: "Post to social media?" Body mentions it can't
be undone. Skip/Approve buttons visible. 14-second countdown
bar progresses.

**Fail signal:** No dialog appears, the decision silently passes
without confirmation, or the countdown bar doesn't move.

### 6. Verification failure screen

**Steps:** This requires a tampered decision. In the test agent,
if a "Trigger verification failure" button exists, tap it. If
not, this check is deferred.

**Expected:** `VerificationFailureScreen` renders instead of the
normal dialog. "Diagnose" button is visible.

**Fail signal:** Normal dialog renders despite tampered
attestation, or the app crashes.

**Note:** // PHASE2A-FOLLOWUP: The current AIDL surface does not
expose a way to inject a tampered decision from the client side.
The kernel always signs correctly. This scenario requires either
a debug hook in the service or a modified test that tampers
post-signature on the service side.

### 7. Technical Detail screen with real data

**Steps:** Run 20+ decisions from the test agent. Open Oak &
Sparrow > Details tab.

**Expected:** Gamma trajectory chart shows a real curve. Attestation
chain shows "100% verified". Calibrator mode shows Warmup or
Steady depending on decision count.

**Fail signal:** Empty chart, "100%%" double percent, or
concatenated reference envelope text.

### 8. App Permissions persistence

**Steps:** Navigate to Apps tab. Change Gmail from "Scoped" to
"Full". Kill the app (`adb shell am force-stop
dev.governance.android`). Reopen.

**Expected:** Gmail still shows "Full".

**Fail signal:** Reverts to default on restart.

### 9. Audit log inspection

**Steps:**
```bash
adb shell run-as dev.governance.android ls files/audit/
adb shell run-as dev.governance.android cat files/audit/audit_active.jsonl.enc
```

**Expected:** The file exists and contains Base64-encoded lines
(one per decision). The file size grows with each decision.

**Fail signal:** No audit directory, empty file, or plaintext
JSON (encryption not working).

### 10. Process death recovery

**Steps:**
```bash
adb shell am force-stop dev.governance.android
```
Then reopen the app.

**Expected:** The foreground service restarts (notification
reappears). The kernel state (gamma, decisions observed)
matches the pre-kill values. The audit log is intact.

**Fail signal:** Fresh defensive-prior state after restart (gamma
reset to 0.85), or the service doesn't restart automatically.

## Capturing evidence

After completing the runbook checks, bundle all evidence:

```bash
./gradlew :android-app:captureEmulatorArtifacts
```

This produces `android/app/build/emulator-evidence/` containing:

- `tests-report/` — connectedAndroidTest HTML report (if tests were run)
- `tests-report-missing.txt` — placeholder if tests haven't been run yet
- `screenshots/` — directory for your manual screenshots (with naming guide)
- `audit/` — pulled audit log from the device (if adb is available)
- `runbook-results.md` — fill-in template for recording check results

The task degrades gracefully: it works whether or not `connectedAndroidTest`
has been run and whether or not adb/emulator is available.
