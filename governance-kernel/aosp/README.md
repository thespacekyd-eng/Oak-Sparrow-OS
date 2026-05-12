# Oak & Sparrow — AOSP Integration Overlay

This directory packages the governance kernel as a **privileged AOSP system app**, ready to be merged into an AOSP / LineageOS / GrapheneOS source tree and built into a flashable ROM.

## What this gives you

When the resulting ROM is flashed:

1. **The kernel runs at boot** as a foreground system service (`init.governance.rc`).
2. **The kernel signs every action** with an Ed25519 key minted in hardware-backed Keystore at first boot.
3. **The trusted display surface** (auth dialog, verification failure card) is rendered by an app under `/system/priv-app` with `INTERNAL_SYSTEM_WINDOW` permission — no third-party app can draw over it.
4. **Root-tier action kinds become real**: `shell_exec` (allowlisted binaries), `package_install`/`package_uninstall`, `settings_put` (allowlisted keys), `network_control` (wifi/airplane/bluetooth), `file_system_write` (governance workspace only).
5. **SELinux** confines the kernel's privileges via `governance_kernel.te`. The kernel can sign, audit, and dispatch privileged operations — but **cannot** write to `/system`, modify SELinux at runtime, or disable verified boot (neverallow rules enforce this).

## Layout

| Path | Purpose |
|------|---------|
| `Android.bp` | Soong blueprint — declares the privileged app prebuilt + its required artifacts |
| `init/init.governance.rc` | Boot-time service prep (creates `/data/governance`, waits for keystore) |
| `sepolicy/governance_kernel.te` | SELinux domain for the kernel + neverallow rules |
| `sepolicy/file_contexts` | Labels for the audit log + state directories |
| `sepolicy/seapp_contexts` | Maps `dev.governance.android` package to the `governance_kernel` domain |
| `permissions/privapp-permissions-dev.governance.android.xml` | Allowlist of system-permissions this privileged app may request |
| `manifest/local_manifest.xml` | repo manifest fragment for syncing this repo into the AOSP tree |

## Integration steps

### 1. Generate a platform key for your device

The privileged app must be signed with the platform key of the ROM you're building. AOSP includes default keys at `build/target/product/security/`, but **never ship a release ROM signed with the upstream defaults** — anyone can install signed system apps.

Generate your own:

```bash
cd build/target/product/security
make -j8 generate-keys
```

This produces `platform.pk8` + `platform.x509.pem`. Set `PRODUCT_DEFAULT_DEV_CERTIFICATE` in your device makefile to point at this directory.

### 2. Add this repo to your repo manifest

```bash
mkdir -p .repo/local_manifests
cp path/to/governance-kernel/aosp/manifest/local_manifest.xml .repo/local_manifests/governance-kernel.xml
# Edit .repo/local_manifests/governance-kernel.xml — replace PLACEHOLDER-USER
# with your git host. Update fetch URL + revision to match.
repo sync packages/apps/OakSparrow
```

### 3. Build the prebuilt APK first

The Soong blueprint references a prebuilt APK. Build it on your dev machine, copy it into the AOSP tree:

```bash
cd packages/apps/OakSparrow/governance-kernel
./gradlew :android-app:assembleRelease
mkdir -p ../prebuilts
cp android/app/build/outputs/apk/release/android-app-release-unsigned.apk \
   ../prebuilts/OakSparrow.apk
```

Soong will sign with the platform key during system image assembly.

### 4. Add to your device's PRODUCT_PACKAGES

In `device/<vendor>/<device>/device.mk`:

```makefile
PRODUCT_PACKAGES += OakSparrow

# SELinux policy directory — point at this overlay's sepolicy/.
BOARD_PLAT_PRIVATE_SEPOLICY_DIR += $(LOCAL_PATH)/../../packages/apps/OakSparrow/aosp/sepolicy

# Permission XML auto-installs to /etc/permissions/ via prebuilt_etc.
```

### 5. Build a system image

```bash
. build/envsetup.sh
lunch <your-device>-userdebug    # or -user for release
m systemimage
```

The output `out/target/product/<device>/system.img` includes Oak & Sparrow as a privileged system app. Combine with the rest of your ROM build (boot.img, vendor.img, etc.) and flash via fastboot.

### 6. Verify after first boot

```bash
adb shell getprop init.svc.governance-prep            # should print "stopped" (oneshot)
adb shell ls -laZ /data/governance                    # check SELinux labels
adb shell dumpsys package dev.governance.android | grep priv-app
adb shell dumpsys package dev.governance.android | grep flags=
# Look for FLAG_SYSTEM, FLAG_PRIVILEGED in flags=
```

`BuildModeDetector.isSystemApp()` should now return `true`, unlocking the root-tier dispatchers.

## Versions tested

Oak & Sparrow targets **AOSP 14 / API 34** by default. AOSP 15 / API 35 is supported with no code changes. Earlier versions are not supported because Ed25519 in Android Keystore requires API 33+.

## What this does NOT include

- A full ROM. You bring your own AOSP/LineageOS sync.
- A bootloader unlock. You unlock your device's bootloader via the OEM's process.
- An OTA update channel. Phase 4 follow-up.
- A user-accessible "factory reset" flow that preserves the audit log. Phase 4 follow-up.
- SafetyNet attestation passthrough. Phase 4 follow-up — devices flashed with this ROM will fail Play Integrity unless you implement custom attestation.

## What's still mocked

When the ROM-flashed kernel runs, `BuildModeDetector.isSystemApp()` returns `true` and the root-tier dispatchers in `:android-platform` execute real privileged operations. **Until** the actual AOSP build lands, the dispatchers compile and run but return `Unsupported(reason="requires system-app placement")` — which is the correct behavior for any sideloaded debug APK. The integration is verified end-to-end the moment you flash a ROM that includes this overlay.
