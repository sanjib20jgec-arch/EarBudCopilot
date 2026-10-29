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
 * Manages Android Native TextToSpeech and routes audio output to connected earphones:
 * - TYPE_WIRED_HEADSET
 * - TYPE_WIRED_HEADPHONES
 * - TYPE_BLUETOOTH_A2DP
 * - TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER
 * - TYPE_BLUETOOTH_SCO
 * - TYPE_USB_HEADSET
 *
 * Uses STREAM_MUSIC / USAGE_MEDIA so that Bluetooth A2DP & SCO earbuds always play clearly.
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
            val bnInLocale = Locale("bn", "IN")
            val bnBdLocale = Locale("bn", "BD")
            val enInLocale = Locale("en", "IN")

            when {
                isVoiceAvailable(engine, bnInLocale) -> {
                    engine.language = bnInLocale
                    onLog("TTS ready: Bengali (India) voice active.")
                }
                isVoiceAvailable(engine, bnBdLocale) -> {
                    engine.language = bnBdLocale
                    onLog("TTS ready: Bengali (Bangladesh) voice active.")
                }
                else -> {
                    engine.language = enInLocale
                    onLog("TTS ready: English (India) voice active.")
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
        } else {
            onLog("TTS Engine failed to initialize (status=$status)")
        }
    }

    private fun isVoiceAvailable(engine: TextToSpeech, locale: Locale): Boolean {
        val code = engine.isLanguageAvailable(locale)
        return code >= TextToSpeech.LANG_AVAILABLE
    }

    fun findConnectedEarphoneDevice(): AudioDeviceInfo? {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Prefer A2DP / Wired / BLE media output first, then SCO
        val sorted = outputDevices.sortedBy { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 1
                else -> 2
            }
        }
        return sorted.firstOrNull { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
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

        // Automatically select Bengali or English locale depending on Unicode script
        val containsBengaliScript = text.any { it.code in 0x0980..0x09FF }
        if (containsBengaliScript) {
            val bnIn = Locale("bn", "IN")
            val bnBd = Locale("bn", "BD")
            when {
                isVoiceAvailable(engine, bnIn) -> engine.language = bnIn
                isVoiceAvailable(engine, bnBd) -> engine.language = bnBd
            }
        } else {
            val enIn = Locale("en", "IN")
            if (isVoiceAvailable(engine, enIn)) {
                engine.language = enIn
            }
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()
        engine.setAudioAttributes(audioAttrs)

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .build()
        audioManager.requestAudioFocus(focusRequest)

        return try {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        onLog("Whispering into earbuds now...")
                    }

                    override fun onDone(id: String?) {
                        if (id == utteranceId && continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId && continuation.isActive) {
                            onLog("TTS playback error.")
                            continuation.resume(false)
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId && continuation.isActive) {
                            onLog("TTS playback error (code=$errorCode).")
                            continuation.resume(false)
                        }
                    }
                })

                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }

                val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (speakResult == TextToSpeech.ERROR && continuation.isActive) {
                    onLog("TTS engine.speak returned ERROR.")
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
