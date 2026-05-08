# Oak & Sparrow — Phase 2B UI

## Screen inventory

| Route | Screen | Description |
|-------|--------|-------------|
| `/home` | `HomeScreen` | Hero card (agent health), observation count, last 5 decisions |
| `/decisions` | `RecentDecisionsScreen` | Full audit log, expandable rows, verify attestation inline |
| `/permissions` | `AppPermissionsScreen` | Per-app Full/Scoped/Denied with sub-action toggles |
| `/technical` | `TechnicalDetailScreen` | Gamma chart, attestation chain, calibrator, system events |
| — | `AuthorizationActivity` | HOLD confirmation dialog with 14s auto-deny countdown |
| — | `OnboardingScreen` | 3-page pager: explain, accessibility, notifications |
| — | `VerificationFailureScreen` | Attestation failure fallback |

## Architecture

- **Single Activity** (`MainActivity`) hosting Compose Navigation
- **Material 3 dynamic color** (`dynamicColor = true`, minSdk 33)
- **Service binding**: `GovernanceKernelService` bound in `onStart`/`onStop`
- **Attestation verification**: `AttestationVerifier.verify()` called before
  rendering any decision-derived state
- **Plain-English copy**: all user-visible strings in `strings.xml`;
  technical vocabulary only on `TechnicalDetailScreen`

## Action template registry

Maps `ProposedAction.kind` to plain-English questions for the HOLD dialog:

| Kind | Question |
|------|----------|
| `send_email` | Send email to {target}? |
| `post_social` | Post to {target}? |
| `delete_file` | Delete {target}? |
| `send_message` | Send message to {target}? |
| `make_payment` | Pay {target}? |
| (fallback) | Approve action: {kind}? |

## Paparazzi screenshots

Record baseline snapshots:
```bash
./gradlew :android-app:recordPaparazziDebug
```

Verify against baseline:
```bash
./gradlew :android-app:verifyPaparazziDebug
```

Note: Paparazzi uses Android layoutlib which requires Linux or macOS.
On Windows, recording may fail with native library errors. Screenshots
are recorded on CI (Linux) and verified locally.

## HTML gallery

```bash
./gradlew :android-app:generateUiGallery
```

Produces `app/build/ui-gallery/index.html` — a self-contained page
showing all screenshots grouped by screen, with light/dark variants
side by side. Also produces `app/build/ui-gallery.zip`.

## Adding a new screen

1. Create composable in `ui/screens/NewScreen.kt`
2. Add navigation route in `MainActivity.MainNavigation`
3. Add string resources to `res/values/strings.xml`
4. Add Paparazzi snapshot test in `screenshots/ScreenshotTests.kt`
5. Run `recordPaparazziDebug` to capture baseline

## Phase follow-up markers

| Marker | Location | Description |
|--------|----------|-------------|
| `PHASE2C-FOLLOWUP` | `build.gradle.kts` | Resolve material-icons-extended for richer icon set |
| `PHASE2C-FOLLOWUP` | `AppPermissionsScreen` | Replace DataStore JSON with Room |
