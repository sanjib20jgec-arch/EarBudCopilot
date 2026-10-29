package com.copilot.earbud.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.copilot.earbud.domain.model.EarphoneConnectionState
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Manages Android Native TextToSpeech and strictly routes audio output to connected earphones:
 * - TYPE_WIRED_HEADSET
 * - TYPE_WIRED_HEADPHONES
 * - TYPE_BLUETOOTH_A2DP
 * - TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER
 * - TYPE_BLUETOOTH_SCO
 * - TYPE_USB_HEADSET
 *
 * Supports Bengali (`bn_IN` / `bn_BD`) and English (`en_IN`) voices for seamless Banglish whispering.
 */
class EarbudTtsManager(
    private val context: Context,
    private val onEarphoneConnectionChanged: (EarphoneConnectionState) -> Unit,
    private val onLog: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshEarphoneStatus()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshEarphoneStatus()
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext, this)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        refreshEarphoneStatus()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            // Prefer Bengali (India) if installed as it reads both Bengali script and English words naturally
            val bnLocale = Locale("bn", "IN")
            val enInLocale = Locale("en", "IN")

            val bnResult = engine.isLanguageAvailable(bnLocale)
            if (bnResult >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = bnLocale
                onLog("TTS initialized with Bengali (India) voice (supports Banglish).")
            } else {
                engine.language = enInLocale
                onLog("TTS initialized with English (India) voice.")
            }

            // Slightly softer pitch and crisp speech rate for discreet earbud whispering
            engine.setSpeechRate(1.05f)
            engine.setPitch(0.95f)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            isTtsReady = true
        } else {
            onLog("TTS Engine failed to initialize (status=$status)")
        }
    }

    /**
     * Checks connected output audio devices for wired or Bluetooth earbuds/headphones.
     */
    fun findConnectedEarphoneDevice(): AudioDeviceInfo? {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputDevices.firstOrNull { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                    } else {
                        false
                    }
                }
            }
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
        onEarphoneConnectionChanged(state)
        return state
    }

    /**
     * Speaks [text] discreetly into the user's connected earphones.
     * If [requireEarphones] is true and no earphones are connected, skips speech to never leak out loud.
     */
    suspend fun whisperToEarbuds(text: String, requireEarphones: Boolean = true): Boolean {
        val engine = tts
        if (!isTtsReady || engine == null) {
            onLog("TTS not ready yet; cannot whisper.")
            return false
        }

        val targetEarphone = findConnectedEarphoneDevice()
        if (requireEarphones && targetEarphone == null) {
            onLog("Whisper blocked: No wired or Bluetooth earphones connected.")
            return false
        }

        // Automatically select Bengali or English locale depending on Unicode script in the response
        val containsBengaliScript = text.any { it.code in 0x0980..0x09FF }
        val targetLocale = if (containsBengaliScript) Locale("bn", "IN") else Locale("en", "IN")
        if (engine.isLanguageAvailable(targetLocale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = targetLocale
        }

        // Request transient ducking audio focus so music/podcast lowers briefly while whispering
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioManager.requestAudioFocus(focusRequest)

        return try {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId && continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId && continuation.isActive) {
                            continuation.resume(false)
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId && continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                })

                val params = Bundle().apply {
                    // Whisper at a comfortable, discreet volume (75% of stream volume)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.75f)
                }

                val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (speakResult == TextToSpeech.ERROR && continuation.isActive) {
                    continuation.resume(false)
                }
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
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            "BLE Audio Earbuds"
        } else {
            "Audio Headset"
        }
    }

    fun shutdown() {
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
    }
}
