# governance-kernel

Temporal-trust governance kernel for autonomous agent action gating.
Phase 1: pure Kotlin/JVM library, no Android dependencies.

## Setup

### Prerequisites
- JDK 17+

### First-time setup

**Option A** — If you have Gradle installed:
```bash
cd governance-kernel
gradle wrapper --gradle-version 8.10
./gradlew check
```

**Option B** — Bootstrap script (requires curl + unzip):
```bash
cd governance-kernel
bash setup.sh
./gradlew check
```

## Module structure

| Module          | Purpose                                           |
|-----------------|---------------------------------------------------|
| `:core`         | Interfaces, data types, no logic                  |
| `:attestation`  | Ed25519 signing, verification, canonical JSON     |
| `:metrics`      | Default γ, entropy, divergence implementations    |
| `:gate`         | Composite decision logic, GovernanceKernel impl   |
| `:audit`        | Content-addressed JSONL audit log                 |
| `:calibration`  | Recursive trust calibration with warmup mode      |
| `:adversarial`  | Synthetic adversarial agents + redteam runner     |
| `:testing`      | Shared fixtures and property generators           |

## Running tests

```bash
./gradlew check          # all tests including adversarial suite
./gradlew :gate:test     # gate tests only
./gradlew :adversarial:test  # adversarial suite only
```
