# Claude Code session — bootstrap for the on-device LLM arc

You are running in parallel with Cowork. Cowork has staged uncommitted
work in `governance-kernel/` (cost-weighted gamma bias + safety margins
in `:core`, `:gate`, `:attestation`) and is blocked on three things only
your environment can resolve. Do them in order.

The standing project instructions in `Oak & Sparrow OS/` apply to you
exactly as they apply to Cowork. Read them first. Do not modify the
kernel modules beyond the work that is already staged. Do not push
anything to a remote.

---

## Task 1 — Verify and commit the staged kernel work

Cowork staged 9 file changes (4 modified, 5 new) but could not commit
because the sandbox can't reach `services.gradle.org` to run tests, and
its mount of `.git/` is read-only.

The staged work is summarized in `governance-kernel/sessions/COMMIT-MSG-pending.txt`
(also produced by Cowork — read it). Verify it before committing.

```bash
cd "Oak & Sparrow OS/governance-kernel"

# 1. Confirm staging matches expectation — these 9 files exactly
git status --short
# Expected output:
#   M  attestation/src/main/kotlin/dev/governance/attestation/DecisionSigner.kt
#   A  core/src/main/kotlin/dev/governance/core/ActionCost.kt
#   M  core/src/main/kotlin/dev/governance/core/Model.kt
#   A  core/src/main/kotlin/dev/governance/core/SafetyMargin.kt
#   A  core/src/test/kotlin/dev/governance/core/ActionCostTest.kt
#   A  core/src/test/kotlin/dev/governance/core/SafetyMarginTest.kt
#   M  gate/src/main/kotlin/dev/governance/gate/CompositeGate.kt
#   M  gate/src/main/kotlin/dev/governance/gate/DefaultGovernanceKernel.kt
#   A  gate/src/test/kotlin/dev/governance/gate/CostWeightedGateTest.kt

# 2. Run the JVM test suite for the affected modules
./gradlew :core:test :gate:test :attestation:test --console=plain

# 3. If green, run the full check to be safe
./gradlew check
```

If any test fails, **stop and report**. Do not "fix" tests by editing
expectations. If a test fails because the implementation has a real
bug, fix the implementation; if it fails because the test expectation
is wrong, that's a finding worth surfacing to the user before changing
anything.

If green, commit with the message Cowork drafted (in
`sessions/COMMIT-MSG-pending.txt`). Use the same author Cowork inferred
from `git log`:

```bash
git -c user.name="thespacekyd-eng" \
    -c user.email="thespacekyd-eng@users.noreply.github.com" \
    commit -F sessions/COMMIT-MSG-pending.txt
```

After committing, delete `sessions/COMMIT-MSG-pending.txt`.

**Do not push.** The standing instructions explicitly forbid push/deploy
without user permission.

---

## Task 2 — Inventory the AOSP build host situation

The user wants to take Oak & Sparrow down the system-app placement path
(`aosp/README.md` documents the integration). They don't remember whether
their machine is set up for AOSP builds. Find out.

Produce `governance-kernel/sessions/aosp-host-inventory.md` with this
structure (fill in **actual values**, don't leave placeholders):

```markdown
# AOSP build host inventory

Generated: <timestamp>
Host OS: <output of `uname -srm` or Windows equivalent>

## Disk space
- <drive>: <free>/<total> (AOSP needs ~200 GB checkout + ~150 GB build out)

## Toolchain presence
- repo: <path or "not installed">
- git: <version or "not installed">
- python3: <version>
- openjdk-17 (or 21): <path or "not installed">
- adb / fastboot: <version or "not installed">
- ccache: <version or "not installed">

## Existing AOSP checkout
- Searched: <list of paths searched>
- Found: <path with manifest.xml or "none">
- If found: branch = <output of `repo info` or `cat .repo/manifest.xml | grep revision`>

## Platform signing keys
- `build/target/product/security/platform.pk8`: <found at <path> | not found>
- If a key was found, fingerprint: <output of openssl on the .x509.pem>
- (If the fingerprint matches the upstream AOSP default, FLAG IT — must regenerate before any release.)

## Target device
- adb-connected devices: <output of `adb devices -l` or "none">
- Bootloader unlock state of each: <output of `adb shell getprop ro.boot.flash.locked` if connected>

## Conclusion
One of:
- "Ready to build path (b) as-is"
- "Have toolchain but no AOSP checkout — need to repo init/sync (~3-6 hours, ~200 GB)"
- "Missing toolchain — need <list>"
- "Not viable on this machine — <reason>" (e.g., Windows host with no WSL2, insufficient disk)
```

Search for an existing AOSP checkout in these locations (in order;
stop at first hit):
- `~/aosp`, `~/android`, `~/AOSP`, `~/src/aosp`, `~/work/aosp`
- `/opt/aosp`, `/mnt/aosp`, `/data/aosp`
- Any directory containing `.repo/manifest.xml` within 3 levels of `~`

Don't run `repo sync`. Don't install any toolchain. Just inventory.

---

## Task 3 — Stop and wait

After Task 1 + Task 2 are complete, post a one-paragraph summary in your
final message and stop. Do not start AOSP work. Do not start LLM
integration. The user is choosing the LLM runtime in the next round
with Cowork; you'll get a follow-up prompt that depends on the
inventory you just produced.

---

## Reporting back

Final report (in your last message, not as a file) needs these four
lines, in order:

```
1. Tests: <pass count> passed / <fail count> failed (cite the test report path)
2. Commit: <sha or "not done — reason">
3. AOSP inventory: see sessions/aosp-host-inventory.md — conclusion line: <copy/paste>
4. Anything surprising: <one line, or "none">
```

That's it. The user is doing visual review and decision-making in
Cowork; your job here is to get the environment ready for the next
session.
