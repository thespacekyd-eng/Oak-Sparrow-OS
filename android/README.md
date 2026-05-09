# Oak & Sparrow — Phase 2A Android Service Infrastructure

## Architecture

Phase 2A wraps the Phase 1 governance kernel in an Android service,
exposing it to agent processes via AIDL/Binder IPC.

```
+---------------------+          AIDL/Binder          +-------------------+
|   Agent Process     | <---------------------------> | Kernel Service    |
| (separate app/proc) |    ProposedAction/Decision    | (GovernanceKernel)|
+---------------------+                               +-------------------+
                                                              |
                                    +--------------------------+-----------+
                                    |              |           |           |
                              AndroidKeystore  AuditWriter  StatePersist  AccessibilitySvc
                              (Ed25519 sign)   (JSONL+AES)  (JSON file)   (outcome obs)
```

### Module layout

| Module | Type | Purpose |
|--------|------|---------|
| `:android-platform` | Android Library | Adapters: `AndroidKeystoreKeyProvider`, `AndroidJsonlAuditWriter`, `SystemClockAdapter`, `AccessibilityObservationService`, Parcelable wrappers, `StatePersistence` |
| `:android-app` | Android Application | `GovernanceKernelService` (foreground), `AgentBinderRateLimiter`, AIDL interface, `OnboardingActivity`, `MainActivity` |

### Dependency rules

- Android modules depend on kernel `:core`, `:attestation`, `:gate`, `:calibration`, `:metrics`, `:audit`
- Android modules do NOT depend on `:adversarial` or `:testing` (except `testImplementation`)
- Kernel modules are NOT modified — they remain pure Kotlin/JVM

## AIDL Interface

```aidl
interface AgentKernelInterface {
    GateDecisionParcel decide(in ProposedActionParcel action);
    void resolve(String decisionAuditId, in ResolvedOutcomeParcel outcome);
    GovernanceSnapshotParcel snapshot();
}
```

Parcelable wrappers use JSON serialization internally for lossless
round-trip. `DecisionAttestation` is preserved exactly — no re-signing
on the wrapper boundary.

### Rate limiting

`AgentBinderRateLimiter` wraps the AIDL binder:
- 50 decisions/second sustained, burst 200 per UID
- Excess requests get HOLD with `RateLimitExceeded` barrier

## Persistence

| Data | Location | Format |
|------|----------|--------|
| Governance state | `filesDir/state/governance_state.json` | JSON, atomic overwrite |
| Audit log (decisions) | `filesDir/audit/*.jsonl.enc` | Per-line AES-256-GCM encrypted JSONL, 10 MB rotation |
| Audit log (system events) | Same file stream | `SystemEventRecord` entries interleaved, identified by `"type":"system_event"` |
| Onboarding | DataStore preferences | Key-value |
| Signing key | Android Keystore | Ed25519 (API 33+) |
| Audit encryption key | Android Keystore | AES-256-GCM |

## Testing

### JVM tests (no emulator needed)

```bash
./gradlew :android-platform:test :android-app:test
```

Tests:
- Parcel JSON round-trip for all kernel data classes
- `StatePersistence` save/load and corruption recovery
- `TokenBucket` rate limiter properties (concurrent, exhaustion, refill)

### Instrumentation tests (API 35 emulator required)

```bash
./gradlew :android-app:connectedAndroidTest
```

Tests:
- Service bind lifecycle
- `decide()` over Binder returns valid signed `GateDecision`
- `decide()` + `resolve()` round-trip
- `snapshot()` returns valid `GovernanceSnapshot`

### Kernel tests (unchanged from Phase 1)

```bash
./gradlew check
```

Phase 1 tests must continue to pass. The `subprojects` block in
`build.gradle.kts` skips Android modules so JVM-only tasks aren't
affected.

## Permissions

| Permission | Purpose |
|------------|---------|
| `FOREGROUND_SERVICE` | Required for foreground service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Subtype `agent_governance` |
| `SYSTEM_ALERT_WINDOW` | Trusted display overlay (Phase 2B) |
| `POST_NOTIFICATIONS` | Service notification (Android 13+) |
| `BIND_ACCESSIBILITY_SERVICE` | Outcome observation |

## Phase follow-up markers

| Marker | Location | Description |
|--------|----------|-------------|
| `PHASE2B-FOLLOWUP` | `AccessibilityObservationService` | Real outcome-resolution heuristics |
| `PHASE2B-FOLLOWUP` | `GovernanceKernelService` | Instrumentation test for startForeground on API 34+ |
| `PHASE2B-FOLLOWUP` | `OnboardingActivity` | Polished UI |
| `PHASE2B-FOLLOWUP` | `MainActivity` | Full governance dashboard |
| `PHASE2C-FOLLOWUP` | `AccessibilityObservationService` | AppFunctions API integration (Android 16+) |

## Build requirements

- JDK 17
- Android SDK with API 35 platform and build-tools
- Gradle 8.10+ with wrapper
- For instrumentation tests: Android emulator (API 35)

## Build modes

| Mode | Signing | Verification | EC fallback |
|------|---------|--------------|-------------|
| **Debug** | Ed25519 (Keystore) or ECDSA P-256 (software fallback) | Algorithm-agile: accepts both Ed25519 and P-256 | Allowed — logs warning, shows banner in UI |
| **Release** | Ed25519 (Keystore) only | Algorithm-agile (same verifier) | **Crashes on launch** with `IllegalStateException` |

Debug builds allow the software ECDSA P-256 fallback so the full
integration (service, IPC, UI, audit) can be exercised on emulators
that lack Ed25519 Keystore support. The home screen shows a warning
banner and the Technical Detail screen renders the signing algorithm
in error color when using the fallback.

Release builds require hardware Ed25519. If the device cannot produce
Ed25519 keys, the service crashes on first launch with a clear message.
To run a release-build APK on an emulator, use a system image that
supports Ed25519 in Keystore (API 35+ with Google APIs on x86_64).

## Supported devices

- **minSdk = 33** (Android 13). Android Keystore Ed25519 requires
  API 33+.
