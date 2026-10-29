# Earbud Ambient AI Copilot (Native Android - Kotlin)

A background Android app (min SDK 26, target/compile SDK 35) that listens to ambient code-mixed
Bengali/English ("Banglish") conversation, decides whether a factual question was asked, and
whispers a one-sentence answer into the connected earbuds.

> **What it actually does (be aware before you install it):** the app records ambient audio
> continuously while listening and uploads **each detected utterance** (up to 10 s of surrounding
> conversation) to a cloud speech-to-text provider. It sends the resulting transcript to a cloud LLM
> and speaks the answer with the on-device Android TTS engine. There is no on-device speech
> recognition, so audio leaves the phone.

---

## Pipeline

```
microphone -> VAD -> WAV chunk -> STT (Groq Whisper / Deepgram) -> LLM (Gemini / Groq) -> TTS -> earbuds
```

1. **`AmbientListeningService`** – foreground service with `foregroundServiceType="microphone"` and a
   `PARTIAL_WAKE_LOCK`. Owns the pipeline and a **single sequential consumer** (`Channel`) so two
   utterances can never overlap.
2. **`AudioCaptureManager`** – 16 kHz / 16-bit / mono `AudioRecord` (`VOICE_RECOGNITION`), 32 ms
   frames, 384 ms pre-roll, 800 ms silence tail, 0.65 s minimum and 10 s maximum utterance,
   **2-of-3 frame vote** for VAD hysteresis.
3. **`SileroVadDetector`** – uses `silero_vad.onnx` from assets when present, otherwise an
   RMS + zero-crossing acoustic detector.
4. **`SttRepository`** – Groq Whisper (`whisper-large-v3-turbo` → `whisper-large-v3`) or Deepgram
   Nova-2, with **cross-provider fallback** and one retry on 429/5xx.
5. **`AiBrainRepository`** – Gemini `generateContent` or Groq chat completions with model discovery
   and fallback. Returns a typed `BrainDecision`: `Answer`, `Ignored` (the model really said
   `[IGNORE]`) or `Failed` (HTTP, auth, quota, empty, truncated or safety-blocked). **A failed or
   empty response is never reported as `[IGNORE]`.**
6. **`EarbudTtsManager`** – process-wide singleton `TextToSpeech`, Bengali/English voice selection,
   `USAGE_MEDIA`, audio focus, wired/A2DP/SCO/BLE/hearing-aid detection, init retry with backoff.
7. **`BootResumeReceiver`** – after a reboot it posts a one-tap "resume listening" notification
   (Android 14+ forbids starting a microphone foreground service from the background).

## Diagnostics

The app has an in-app activity log with these tags:

| Tag | Meaning |
|---|---|
| `VAD` | an utterance chunk was captured / queued |
| `STT` | transcript (`Heard: "..."`) or "too short" |
| `STT_ERR` / `LLM_ERR` | the stage failed - the message names the cause |
| `FILTER` | the model deliberately classified the speech as non-factual |
| `ANSWER` | an answer was generated (highlighted) |
| `TTS` | spoken / blocked (no earphones) / engine not ready |
| `CONFIG` | the pipeline refused to start because a key is missing |

The **Run Diagnostics** button reports TTS readiness, earphone detection and whether each provider
key authenticates. Pipeline stage colours: blue = running, yellow = speech, green = speaking,
grey = filtered, red = error.

## Build

```bash
gradle assembleDebug
```

CI (`.github/workflows/main.yml`) builds on every push and runs the JVM unit tests
(`app/src/test`) before packaging. Without signing secrets it produces a **debug-signed** APK; because
the CI debug keystore is regenerated on every run, installing an update over an older build fails
until you uninstall, which also erases the stored API keys. To get stable, updatable builds, add
these repository secrets:

| Secret | Meaning |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | base64 of your `.jks` (`base64 -w0 release.jks`) |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

When they are present the workflow builds `assembleRelease` signed with your key; otherwise it falls
back to a debug build. Locally you can put the same values in `keystore.properties` (git-ignored) or
export them as environment variables.

## Security notes

- API keys live in `EncryptedSharedPreferences` (AES256-GCM, Keystore-backed) and are migrated from
  the old plaintext file on first launch. They are never displayed again after saving and never
  carried in UI state.
- Gemini authentication uses the `x-goog-api-key` header (not a URL query parameter).
- Audio and transcripts are **not** written to disk or to logcat; the activity log is memory-only.
