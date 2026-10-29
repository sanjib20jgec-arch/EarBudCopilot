package com.copilot.earbud.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.copilot.earbud.domain.model.EarphoneConnectionState
import com.copilot.earbud.domain.model.TtsOutcome
import com.copilot.earbud.service.CopilotStateBus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Manages Android Native TextToSpeech and routes audio output to connected earphones:
 * - TYPE_WIRED_HEADSET / TYPE_WIRED_HEADPHONES / TYPE_USB_HEADSET
 * - TYPE_BLUETOOTH_A2DP / TYPE_BLUETOOTH_SCO
 * - TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER / TYPE_BLE_BROADCAST (API 31+)
 * - TYPE_HEARING_AID (API 31+)
 *
 * Process-wide singleton: the foreground service and the UI share ONE engine binding. Two parallel
 * `TextToSpeech` instances used to fight over locale, audio attributes and device callbacks.
 * Initialization is retried with backoff, because a single failed `onInit` used to leave the app
 * permanently unable to speak while still reporting itself as listening.
 */
class EarbudTtsManager private constructor(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val MAX_INIT_ATTEMPTS = 3
        private const val INIT_RETRY_DELAY_MS = 2000L

        /** Upper bound for one spoken answer, so a stuck engine can never freeze the pipeline. */
        private const val SPEAK_TIMEOUT_MS = 20_000L

        @Volatile
        private var instance: EarbudTtsManager? = null

        fun get(context: Context): EarbudTtsManager =
            instance ?: synchronized(this) {
                instance ?: EarbudTtsManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext: Context = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var initAttempts = 0
    private var initInFlight = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshEarphoneStatus()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshEarphoneStatus()
        }
    }

    init {
        startEngine()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        refreshEarphoneStatus()
    }

    val isReady: Boolean
        get() = isTtsReady

    private fun log(message: String) {
        CopilotStateBus.log("TTS", message)
    }

    private fun startEngine() {
        if (initInFlight) return
        initInFlight = true
        runCatching { tts?.shutdown() }
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        initInFlight = false
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine == null) {
                isTtsReady = false
                scheduleRetry("TTS engine handle was lost")
                return
            }

            val bnInLocale = Locale("bn", "IN")
            val bnBdLocale = Locale("bn", "BD")
            val enInLocale = Locale("en", "IN")

            when {
                isVoiceAvailable(engine, bnInLocale) -> {
                    engine.language = bnInLocale
                    log("TTS ready: Bengali (India) voice active.")
                }
                isVoiceAvailable(engine, bnBdLocale) -> {
                    engine.language = bnBdLocale
                    log("TTS ready: Bengali (Bangladesh) voice active.")
                }
                else -> {
                    engine.language = enInLocale
                    log("TTS ready: English (India) voice active (no Bengali voice found).")
                }
            }

            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build()
            )
            isTtsReady = true
            initAttempts = 0
        } else {
            isTtsReady = false
            scheduleRetry("TTS engine failed to initialize (status=$status)")
        }
    }

    private fun scheduleRetry(reason: String) {
        initAttempts++
        if (initAttempts > MAX_INIT_ATTEMPTS) {
            log(
                "$reason. Gave up after $MAX_INIT_ATTEMPTS attempts — enable a speech engine and voice " +
                    "data in Android Settings > Text-to-speech output."
            )
            return
        }
        log("$reason. Retry $initAttempts/$MAX_INIT_ATTEMPTS in ${INIT_RETRY_DELAY_MS / 1000}s.")
        mainHandler.postDelayed({ startEngine() }, INIT_RETRY_DELAY_MS)
    }

    /** Best-effort recovery hook used right before speaking. */
    fun ensureReady(): Boolean {
        if (isTtsReady) return true
        if (!initInFlight && initAttempts <= MAX_INIT_ATTEMPTS) {
            log("TTS not ready; retrying engine initialization before speaking.")
            initAttempts = 0
            startEngine()
        }
        return isTtsReady
    }

    private fun isVoiceAvailable(engine: TextToSpeech, locale: Locale): Boolean {
        val code = engine.isLanguageAvailable(locale)
        return code >= TextToSpeech.LANG_AVAILABLE
    }

    fun findConnectedEarphoneDevice(): AudioDeviceInfo? {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Prefer A2DP / wired / BLE media output first, then everything else
        val sorted = outputDevices.sortedBy { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> 1
                else -> 2
            }
        }
        return sorted.firstOrNull { device -> isHeadphoneType(device.type) }
    }

    private fun isHeadphoneType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
                type == AudioDeviceInfo.TYPE_HEARING_AID
        } else {
            false
        }
    }

    fun refreshEarphoneStatus(): EarphoneConnectionState {
        val device = findConnectedEarphoneDevice()
        val state = if (device != null) {
            val typeLabel = getDeviceTypeName(device.type)
            val productName = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel
            EarphoneConnectionState(
                isConnected = true,
                deviceName = productName,
                deviceTypeDescription = "Connected ($typeLabel) • Private Whisper Ready"
            )
        } else {
            EarphoneConnectionState(
                isConnected = false,
                deviceName = "No Earphone Detected",
                deviceTypeDescription = "Loudspeaker Blocked for Privacy"
            )
        }
        CopilotStateBus.updateEarphoneState(state)
        return state
    }

    suspend fun whisperToEarbuds(text: String, requireEarphones: Boolean = true): TtsOutcome {
        if (text.isBlank()) {
            log("Nothing to speak: the answer text was empty.")
            return TtsOutcome.ENGINE_ERROR
        }

        val engine = tts
        if (!isTtsReady || engine == null) {
            if (!ensureReady()) {
                log("TTS engine is not ready yet; cannot whisper. Check Android Settings > Text-to-speech output.")
                return TtsOutcome.NOT_READY
            }
        }
        val readyEngine = tts ?: return TtsOutcome.NOT_READY

        val targetEarphone = findConnectedEarphoneDevice()
        if (requireEarphones && targetEarphone == null) {
            log("Whisper blocked: No wired or Bluetooth earphones connected.")
            return TtsOutcome.NO_EARPHONES
        }

        // Automatically select Bengali or English locale depending on Unicode script
        val containsBengaliScript = text.any { it.code in 0x0980..0x09FF }
        if (containsBengaliScript) {
            val bnIn = Locale("bn", "IN")
            val bnBd = Locale("bn", "BD")
            when {
                isVoiceAvailable(readyEngine, bnIn) -> readyEngine.language = bnIn
                isVoiceAvailable(readyEngine, bnBd) -> readyEngine.language = bnBd
                // No Bengali voice on this device: the engine may read Bengali script with a
                // non-Bengali voice and stay silent. Say so explicitly instead of failing quietly.
                else -> log(
                    "Bengali voice data is missing on this device; the Bengali answer may be silent or " +
                        "mispronounced. Install a Bengali voice in Android Settings > Text-to-speech output."
                )
            }
        } else {
            val enIn = Locale("en", "IN")
            if (isVoiceAvailable(readyEngine, enIn)) {
                readyEngine.language = enIn
            }
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()
        readyEngine.setAudioAttributes(audioAttrs)

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .build()
        audioManager.requestAudioFocus(focusRequest)

        return try {
            // Hard stop: without a timeout a silent/never-finishing utterance kept the single
            // pipeline consumer suspended forever, so the app looked frozen (mic full, no answers,
            // no stage change) until it was force-stopped.
            withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
                suspendCancellableCoroutine<TtsOutcome> { continuation ->
                    val utteranceId = UUID.randomUUID().toString()
                    val deviceLabel = targetEarphone
                        ?.productName
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: "default audio output"

                    readyEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {
                            log("Speaking via $deviceLabel ...")
                        }

                        override fun onDone(id: String?) {
                            if (id == utteranceId && continuation.isActive) {
                                continuation.resume(TtsOutcome.SPOKEN)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId && continuation.isActive) {
                                log("TTS playback error.")
                                continuation.resume(TtsOutcome.ENGINE_ERROR)
                            }
                        }

                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId && continuation.isActive) {
                                log("TTS playback error (code=$errorCode).")
                                continuation.resume(TtsOutcome.ENGINE_ERROR)
                            }
                        }
                    })

                    val params = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }

                    val speakResult = readyEngine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    if (speakResult == TextToSpeech.ERROR && continuation.isActive) {
                        log("TTS engine.speak returned ERROR.")
                        continuation.resume(TtsOutcome.ENGINE_ERROR)
                    }

                    continuation.invokeOnCancellation {
                        runCatching { readyEngine.stop() }
                    }
                }
            } ?: run {
                runCatching { readyEngine.stop() }
                log(
                    "The answer did not finish speaking within ${SPEAK_TIMEOUT_MS / 1000}s " +
                        "(engine or voice data stuck); stopped it so listening can continue."
                )
                TtsOutcome.ENGINE_ERROR
            }
        } finally {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private fun getDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP Earbuds"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset (SCO)"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C Headset"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (type) {
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Audio Earbuds"
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Speaker"
                AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
                else -> "Audio Headset"
            }
        } else {
            "Audio Headset"
        }
    }

    /** Called when the whole app goes away; the singleton is kept for the process lifetime otherwise. */
    fun shutdown() {
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isTtsReady = false
        synchronized(Companion::class.java) {
            if (instance === this) instance = null
        }
    }
}
