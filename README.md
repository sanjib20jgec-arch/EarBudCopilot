# Earbud Ambient AI Copilot (Native Android - Kotlin)

A persistent background Android application in Kotlin (Min SDK 26, Target/Compile SDK 35) that continuously listens to ambient code-mixed Bengali and English (**Banglish**) conversations—even when the screen is off/locked—and discreetly whispers single-sentence answers to factual questions directly into your connected earphones.

---

## Architecture & Core Components

1. **`AndroidManifest.xml` (Android 14 / SDK 34+ Strict Compliance)**
   - Declares `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `INTERNET`, `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
   - Registers `AmbientListeningService` with `android:foregroundServiceType="microphone"`.

2. **`AmbientListeningService.kt` (Persistent Foreground Service + WakeLock)**
   - Runs as an ongoing foreground microphone service with `PowerManager.PARTIAL_WAKE_LOCK` (`EarbudAmbientCopilot::AmbientMicWakeLock`) so the CPU never enters deep sleep while the phone screen is locked.
   - Orchestrates the asynchronous pipeline: `AudioCaptureManager` -> `SttRepository` -> `AiBrainRepository` -> `EarbudTtsManager`.
   - Automatically pauses microphone capture while `EarbudTtsManager` is whispering so the assistant never triggers on its own voice.

3. **`AudioCaptureManager.kt` + `SileroVadDetector.kt` + `WavEncoder.kt`**
   - Captures 16kHz, 16-bit PCM Mono audio via `AudioRecord` (`VOICE_RECOGNITION` source).
   - **Hybrid VAD**: Automatically loads `silero_vad.onnx` via `com.microsoft.onnxruntime:onnxruntime-android` if placed in `app/src/main/assets/silero_vad.onnx`, and seamlessly falls back to a real-time acoustic RMS + Zero-Crossing Rate (ZCR) voice detector out of the box.
   - Maintains a **384ms pre-roll ring buffer** so initial consonants aren't clipped, accumulates active speech (up to 10s max chunk), and finalizes the chunk when silence exceeds **800ms**, encoding a RIFF WAVE file in-memory (`WavEncoder.kt`).

4. **`SttRepository.kt` (Banglish Speech-to-Text)**
   - Supports **Groq Whisper (`whisper-large-v3`)** via multipart upload with a code-mixed Bengali/English prompt hint (`কলকাতা থেকে দিঘার দূরত্ব কত? Who is the CEO of Google?`) for sub-second Banglish transcription.
   - Supports **Deepgram Nova-2 (`nova-2`)** as a toggleable alternative.

5. **`AiBrainRepository.kt` (Intent & Question Filter)**
   - Supports **Google Gemini 2.0 Flash** (`gemini-2.0-flash`) and **Groq Llama 3.3 70B** (`llama-3.3-70b-versatile`).
   - Enforces the exact Banglish system prompt: outputs `[IGNORE]` for social/personal remarks (`চা খাবি?`, `তুই কেমন আছিস?`) and outputs a crisp, 1-sentence factual answer (max 12–15 words) in the same language mix when an objective question is detected.

6. **`EarbudTtsManager.kt` (Earphone-Only Whisper Output)**
   - Monitors connected audio outputs via `AudioManager.registerAudioDeviceCallback` (`TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET`, `TYPE_BLUETOOTH_SCO`, `TYPE_USB_HEADSET`).
   - Automatically switches between Bengali (`bn_IN`) and English (`en_IN`) TTS voices based on script detection and blocks loudspeaker playback when strict privacy mode is enabled.

---

## How to Build & Run in Android Studio

1. Extract `EarbudAmbientCopilot_AndroidProject.zip` and open the `EarbudAmbientCopilot` folder in **Android Studio Ladybug (2024.2.1) or newer**.
2. Let Gradle sync and download dependencies (SDK 35, Jetpack Compose, OkHttp, ONNX Runtime).
3. *(Optional)* If you want neural Silero VAD in addition to the built-in RMS+ZCR VAD, place `silero_vad.onnx` inside `app/src/main/assets/silero_vad.onnx`.
4. Connect your Android device (Android 8.0+ / API 26–35), run the app, enter your **Groq API Key** and/or **Gemini API Key**, connect your earbuds, and tap **START**.
