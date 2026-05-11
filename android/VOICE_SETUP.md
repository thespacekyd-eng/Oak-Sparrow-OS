# Voice setup — testing Oak & Sparrow as a Siri replacement

This walkthrough takes you from a freshly-built APK (with the on-device
LLM working per `MODEL_SETUP.md`) to a phone where long-press home
launches Oak & Sparrow, you speak a request, the kernel evaluates it,
and the assistant speaks back.

Prereqs:
- `MODEL_SETUP.md` steps 1–4 done. The chat surface should already
  show the "On-device LLM ready" system message before you start.
- A Pixel device (6 or newer recommended) running Android 12+. On
  non-Pixel devices, on-device speech recognition may fall back to a
  network path — and since the app has no `INTERNET` permission, that
  will fail. See "Hardware notes" below.

---

## What gets you Siri-likeness

Three pieces stack on the Phase A LLM:

1. **Mic button on the chat surface.** Tap it, talk, the transcript
   becomes your message, the planner runs through the kernel, the
   response is spoken via TTS. Works without setting Oak & Sparrow as
   the default assistant.
2. **TTS on responses.** Voice-initiated requests get a spoken reply;
   typed messages don't (so your phone doesn't randomly start talking).
3. **`AssistantActivity` registered for `ACTION_ASSIST`.** Once you set
   Oak & Sparrow as the device's Digital assistant app, long-press home
   (or whatever your device's assist gesture is) launches the immersive
   overlay, auto-listens, and runs the same Planner → Kernel →
   Dispatcher pipeline.

What's *not* in this slice: hotword detection ("Hey Oak"). True
always-listening requires either a partnership with Google's keyword
detector or a custom KWS model running in a foreground service —
deferred per the project plan.

---

## 1. Grant the microphone permission

After the next install:

```bash
./gradlew :android-app:installDebug
```

Open the app, navigate to chat, tap the mic icon. Android shows the
runtime permission prompt. Grant it. The app remembers the grant.

You can also grant non-interactively:

```bash
adb shell pm grant dev.governance.android android.permission.RECORD_AUDIO
```

Verify:

```bash
adb shell dumpsys package dev.governance.android | grep RECORD_AUDIO
# Look for: android.permission.RECORD_AUDIO: granted=true
```

---

## 2. Test the in-chat mic

This is the simplest happy path — no system settings, no assistant
role, just verify the voice loop works.

1. Open the chat surface.
2. Tap the mic icon (left of the text field).
3. The icon turns red and the placeholder reads "Listening...".
4. Say something the planner handles, e.g. *"check my calendar"*.
5. The transcript appears live in the input field as you speak.
6. When recognition finalizes (~1 sec of silence), the mic releases,
   the message sends, the planner runs through the kernel, and the
   summary is spoken back to you.

Watch logcat for the full trace:

```bash
adb logcat -s OakSparrowVoice OakSparrowLLM
```

You should see:

```
OakSparrowVoice: voice: Idle -> Listening
OakSparrowVoice: voice: using createOnDeviceSpeechRecognizer (guaranteed local)
OakSparrowVoice: voice: Listening -> Heard
OakSparrowLLM: nativeGenerate: prompt 287 tokens, max_new=1024
OakSparrowLLM: nativeGenerate: 142 tokens in 8420ms (16.9 tok/s, ...)
OakSparrowVoice: voice: Heard -> Speaking
OakSparrowVoice: voice: Speaking -> Idle
```

If you see *"voice: on-device recognizer unavailable; falling back…"*
your device doesn't have an on-device speech recognizer installed; see
"Hardware notes."

---

## 3. Make Oak & Sparrow the system Digital assistant

This is the step that wires up long-press home → Oak & Sparrow.

### Pixel / stock Android 12+

1. **Settings → Apps → Default apps → Digital assistant app**
2. Pick **Oak & Sparrow** from the list. *(If it's not in the list, the
   APK didn't land with the `ACTION_ASSIST` intent-filter — confirm
   step 4 below.)*

That's it. The next time you long-press home (or use whatever assist
gesture your device uses — power button on Pixel 8+ in some configs),
`AssistantActivity` launches over your current app.

### Quick command-line shortcut

```bash
adb shell settings put secure assistant \
    "dev.governance.android/dev.governance.android.app.AssistantActivity"
adb shell settings put secure voice_interaction_service ""
```

(The empty `voice_interaction_service` clears any existing voice
service binding so the assist gesture goes through `ACTION_ASSIST`
intent dispatch — which is the path our manifest registers.)

Verify:

```bash
adb shell settings get secure assistant
# Expect: dev.governance.android/.AssistantActivity (or full class)
```

### Step 4 sanity check — confirm the intent-filter is present

```bash
adb shell dumpsys package dev.governance.android | grep -A 3 ACTION_ASSIST
# Expect entry mentioning AssistantActivity
```

---

## 4. Test the assist gesture

1. Long-press the home button (or your device's assist gesture).
2. The Oak & Sparrow overlay slides up over whatever you were doing.
3. The mic icon pulses; "Listening..." appears.
4. Say *"open Instagram"*.
5. The kernel-gated dispatcher fires. The overlay says
   *"Open Instagram"*, speaks it, then auto-closes after ~800 ms.
6. Behind the now-closed overlay, Instagram opens.

If the action requires authorization (`HOLD` from the kernel), the
existing `AuthorizationActivity` dialog appears — voice doesn't bypass
that.

If the action is vetoed, the overlay says the rationale and stays
visible until you tap close or the auto-close fires.

---

## Verifying the kernel still gates everything

The whole point of this design is that voice is just another input
modality — it doesn't grant the LLM any new powers. Quick sanity
check:

1. Open Oak & Sparrow, navigate to **Permissions** (or wherever you
   manage app capabilities).
2. Restrict the dispatcher's allowed actions for, say, Instagram.
3. Long-press home, say *"share this to Instagram"*.
4. The kernel returns `VETO`. The overlay says so. Nothing posted.

`adb logcat -s OakSparrowKernel OakSparrowLLM OakSparrowVoice` shows
the full trace: voice → planner → AIDL roundtrip → kernel decide() →
veto → spoken reply.

---

## Hardware notes

| Device class                                    | On-device speech?                       |
| ----------------------------------------------- | --------------------------------------- |
| Pixel 6 / 7 / 8 / 9 (any Tensor SoC)            | Yes, via Google's on-device model       |
| Samsung S24 / Fold / Flip with Bixby            | Mixed — Bixby's recognizer may be local but isn't routed through `createOnDeviceSpeechRecognizer` until you set it as default |
| Other Android 12+                               | Depends on installed speech provider    |
| Older than Android 12                           | Not supported (we target API 33+)       |

If `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` returns
false on your device, the Voice flow surfaces the friendly error
*"No on-device speech recognition available on this device."* and the
mic button reflects that state. To get on-device recognition on a
non-Pixel:

- Install Google's "Speech Recognition & Synthesis" package (usually
  preinstalled on Google services devices) and pick it as the system
  speech provider in Settings → Apps → Default apps → Assist & voice
  input.

---

## TTS engine

The default Android `TextToSpeech` is on-device on all modern devices.
If you don't hear anything when the assistant should be speaking:

```bash
adb shell pm list packages | grep -i tts
# Expect: com.google.android.tts (or vendor equivalent)
```

If empty, install the system TTS engine from Play, or your vendor's
equivalent. The `<queries>` entry in our manifest already declares the
`TTS_SERVICE` intent so the app can detect installed engines on
Android 11+.

---

## Privacy stance — what stays on device

| Data                  | Where it stays                          |
| --------------------- | --------------------------------------- |
| Audio (raw)           | Recognizer process; never enters this app |
| Transcript            | App memory only; not persisted          |
| Plan / kernel decision| App private files (`/data/data/.../files/audit/...`), encrypted |
| TTS audio             | Speaker output; not recorded            |

The `AndroidManifest.xml` has no `INTERNET` permission. The app's
process literally cannot make a socket connection. Audit by:

```bash
adb shell dumpsys package dev.governance.android | grep -i internet
# Expect: nothing
```

---

## Troubleshooting

**Mic icon never appears.**
`SpeechRecognizer.isRecognitionAvailable(context)` returned false. See
"Hardware notes."

**Mic icon appears, tap → permission prompt appears, tap → nothing.**
The permission was denied. Re-tap the mic; it'll prompt again. If
permanently denied, grant via Settings → Apps → Oak & Sparrow →
Permissions → Microphone.

**Tap mic → nothing happens, no permission prompt.**
Grant already granted but recognizer init failed. Check logcat for the
specific error.

**Long-press home does nothing / opens Google Assistant instead.**
Default assistant role is set to Google. Re-do step 3.

**Long-press home opens Oak & Sparrow but mic permission is denied.**
The overlay shows the friendly explanation. Tap "Try again" after
granting via Settings.

**TTS speaks but cuts off / clipped.**
TTS engine is busy. The controller uses `QUEUE_FLUSH` so a new
utterance always replaces the previous; if you keep hearing
truncations, your installed TTS engine has known latency issues — try
a different one.

**The plan summary is spoken in robot monotone.**
Default Android TTS voice. Swap voices via Settings → Accessibility →
Text-to-speech output → Preferred engine → settings → install voice
data.
