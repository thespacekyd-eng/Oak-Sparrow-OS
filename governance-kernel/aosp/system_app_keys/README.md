# Platform Key — `OakSparrow.apk`

The privileged app under `/system/priv-app/OakSparrow/` must be signed with the platform key of the ROM you're building. **Do not ship a release ROM signed with the upstream AOSP default keys** — those keys are public and anyone can install signed system apps.

## Generating

```bash
cd build/target/product/security
make -j8 generate-keys
# Produces: platform.pk8, platform.x509.pem, releasekey.pk8, releasekey.x509.pem
```

Set in your device makefile:

```makefile
PRODUCT_DEFAULT_DEV_CERTIFICATE := build/target/product/security/platform
```

## Signing the prebuilt manually (out-of-tree builds)

```bash
java -jar prebuilts/sdk/tools/lib/signapk.jar \
    -w build/target/product/security/platform.x509.pem \
       build/target/product/security/platform.pk8 \
       packages/apps/OakSparrow/prebuilts/OakSparrow-unsigned.apk \
       packages/apps/OakSparrow/prebuilts/OakSparrow.apk
```

## Verifying the signature on-device

```bash
adb shell pm dump dev.governance.android | grep -A 2 signatures
# Should show the SHA-256 fingerprint of platform.x509.pem
```

If the fingerprint matches the upstream AOSP default — you forgot to generate your own keys. Stop, regenerate, rebuild.
