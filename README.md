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
   frames, 384 ms pre-roll, **550 ms silence tail**, 0.65 s minimum and 10 s maximum utterance,
   **2-of-3 frame vote** for VAD hysteresis, plus platform `AcousticEchoCanceler`.
   The microphone is **not muted while the answer is spoken**: capture continues so surrounding
   voices are not missed, and each chunk is tagged with whether it overlapped our own TTS.
3. **`SileroVadDetector`** – uses `silero_vad.onnx` from assets when present, otherwise an
   RMS + zero-crossing acoustic detector.
4. **`SttRepository`** – Groq Whisper (`whisper-large-v3-turbo` for *Fast*, `whisper-large-v3` for
   *Accurate*) or Deepgram Nova-2, with **cross-provider fallback**, an optional **language hint**
   (Auto / Bengali / English) and one retry on 429/5xx.
5. **`AiBrainRepository`** – Gemini `generateContent` or Groq chat completions with model discovery
   and fallback. Returns a typed `BrainDecision`: `Answer`, `Ignored` (the model really said
   `[IGNORE]`) or `Failed` (HTTP, auth, quota, empty, truncated or safety-blocked). **A failed or
   empty response is never reported as `[IGNORE]`.**
6. **`EarbudTtsManager`** – process-wide singleton `TextToSpeech`, Bengali/English voice selection,
   `USAGE_MEDIA`, audio focus, wired/A2DP/SCO/BLE/hearing-aid detection, init retry with backoff.
7. **`BootResumeReceiver`** – after a reboot it posts a one-tap "resume listening" notification
   (Android 14+ forbids starting a microphone foreground service from the background).

## Settings

| Setting | Effect |
|---|---|
| **Keep Listening While Speaking** | microphone stays open during the reply (default ON). Our own voice is dropped by `SelfEchoDetector` instead of muting the mic |
| **Transcription Language** | `Auto`, `Bengali (bn)` or `English (en)` hint sent to Whisper/Deepgram - the single biggest lever for Banglish accuracy |
| **Whisper Model** | `Fast` (`large-v3-turbo`, lowest latency) or `Accurate` (`large-v3`) |

## Performance & robustness

The UI keeps a **single slow-changing state object**; the activity log, the live mic level and the
pipeline stage are separate flows collected only by the small composables that render them, so an
audio frame can no longer recompose the whole screen. API-key typing and the VAD slider are
debounced and persisted off the main thread, and `AudioRecord`/VAD setup runs on `Dispatchers.IO`
instead of blocking `onStartCommand`.

The pipeline heals itself:

| Failure | Behaviour |
|---|---|
| TTS never finishes an utterance | stopped after 20 s, answer marked failed, listening continues |
| One utterance stalls (network) | abandoned after a 45 s budget, stage reset to listening |
| Any exception in a stage | logged as `PIPELINE_ERR`, the consumer survives it |
| `AudioRecord.read()` fails | backs off instead of spinning, gives up after 20 tries |
| Microphone lost (other app, device unplugged) | capture restarted up to 3 times |
| START pressed while "running" but capture is dead | pipeline is rebuilt (no dead button) |

While the answer is spoken the mic stays open, but the voice gate is raised by 1.5x so earbud bleed
does not re-trigger the pipeline.

## Diagnostics

The app has an in-app activity log with these tags. Newest entries are on top and the panel stays
pinned to the newest line, so no scrolling is needed while the copilot is running; if you scroll
back to read history, a **↑ Newest** button (with the number of lines that arrived meanwhile) jumps
back up:

| Tag | Meaning |
|---|---|
| `VAD` | an utterance chunk was captured / queued |
| `STT` | transcript (`Heard: "..."`) or "too short" |
| `STT_ERR` / `LLM_ERR` | the stage failed - the message names the cause |
| `FILTER` | the model deliberately classified the speech as non-factual |
| `ECHO` | ignored: the mic picked up our own answer (`SelfEchoDetector`), surrounding voices are still captured |
| `TIMEOUT` / `PIPELINE_ERR` | an utterance exceeded its budget or a stage threw - listening continued |
| `AUDIO_ERR` | the microphone failed; capture is restarted automatically (bounded) |
| `PERF` | per-utterance timings in ms: STT, AI, speak and end-to-end |
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
