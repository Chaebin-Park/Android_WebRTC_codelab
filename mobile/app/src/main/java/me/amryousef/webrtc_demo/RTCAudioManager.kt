package me.amryousef.webrtc_demo

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.MODE_IN_COMMUNICATION
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import androidx.preference.PreferenceManager
import android.util.Log
import org.webrtc.ThreadUtils
import java.util.HashSet

class RTCAudioManager constructor(context: Context) {

    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, NONE
    }

    enum class AudioManagerState {
        UNINITIALIZED, PREINITIALIZED, RUNNING
    }

    interface AudioManagerEvents {
        fun onAudioDeviceChanged(
            selectedAudioDevice: AudioDevice?,
            availableAudioDevices: Set<AudioDevice?>?
        )
    }

    private var apprtcContext: Context
    private var audioManager: AudioManager
    private var audioManagerEvents: AudioManagerEvents? = null
    private var amState: AudioManagerState? = null
    private var savedAudioMode = AudioManager.MODE_INVALID
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var hasWiredHeadset = false

    private var defaultAudioDevice: AudioDevice? = null
    private var selectedAudioDevice: AudioDevice? = null
    private var userSelectedAudioDevice: AudioDevice? = null
    private var useSpeakerphone: String? = null
    private var audioDevices: MutableSet<AudioDevice> = HashSet()
    private var wiredHeadsetReceiver: BroadcastReceiver? = null
    private var audioFocusChangeListener: OnAudioFocusChangeListener? = null

    init {
        ThreadUtils.checkIsOnMainThread()
        apprtcContext = context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = true
        audioManager.mode = MODE_IN_COMMUNICATION
        wiredHeadsetReceiver = WiredHeadsetReceiver()
        amState = AudioManagerState.UNINITIALIZED
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        useSpeakerphone = sharedPreferences.getString(
            context.getString(R.string.pref_speakerphone_key),
            context.getString(R.string.pref_speakerphone_default)
        )
        defaultAudioDevice = if (useSpeakerphone === SPEAKERPHONE_FALSE) {
            AudioDevice.EARPIECE
        } else {
            AudioDevice.SPEAKER_PHONE
        }
    }

    fun create(): RTCAudioManager {
        return RTCAudioManager(apprtcContext)
    }

    private fun start(audioManagerEvents: AudioManagerEvents) {
        ThreadUtils.checkIsOnMainThread()
        if (amState == AudioManagerState.RUNNING) {
            return
        }
        this.audioManagerEvents = audioManagerEvents
        amState = AudioManagerState.RUNNING

        savedAudioMode = audioManager.mode
        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute
        hasWiredHeadset = hasWiredHeadset()

        audioFocusChangeListener = OnAudioFocusChangeListener {
            var typeOfChange: String? = null
            when (it) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    typeOfChange = "AUDIOFOCUS_GAIN"
                }
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                    typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT"
                }
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                    typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"
                }
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                    typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    typeOfChange = "AUDIOFOCUS_LOSS"
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT"
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
                }
                else -> {
                    typeOfChange = "AUDIOFOCUS_INVALID"
                }
            }
            Log.d(TAG, "onAudioFocusChange:$typeOfChange")
        }

        var result: Int = 0
        result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(
                AudioFocusRequest.Builder(AudioManager.STREAM_VOICE_CALL)
                    .setFocusGain(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
                    .build())
        } else {
            audioManager.requestAudioFocus(audioFocusChangeListener,AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setMicrophoneMute(false)
        userSelectedAudioDevice = AudioDevice.NONE
        audioDevices.clear()

        updateAudioDeviceState()

        registerReceiver(wiredHeadsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
    }

    @SuppressLint("WrongConstant")
    private fun stop() {
        ThreadUtils.checkIsOnMainThread()
        if (amState != AudioManagerState.RUNNING) {
            return
        }
        amState = AudioManagerState.UNINITIALIZED
        unregisterReceiver(wiredHeadsetReceiver)

        setSpeakerphoneOn(savedIsSpeakerPhoneOn)
        setMicrophoneMute(savedIsMicrophoneMute)
        audioManager.mode = savedAudioMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(
                AudioFocusRequest.Builder(AudioManager.STREAM_VOICE_CALL)
                    .setFocusGain(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
                    .build()
            )
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        audioFocusChangeListener = null
    }

    /**
     * Changes default audio device.
     */
    fun setDefaultAudioDevice(defaultDevice: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        when {
            AudioDevice.SPEAKER_PHONE == defaultDevice -> {
                defaultAudioDevice = defaultDevice
            }
            AudioDevice.EARPIECE == defaultDevice -> {
                defaultAudioDevice = if (hasEarpiece()) {
                    defaultDevice
                } else {
                    AudioDevice.SPEAKER_PHONE
                }
            }
            else -> {
                Log.e(TAG, "Invalid default audio device selection")
            }
        }
        Log.d(TAG, "setDefaultAudioDevice(device=\$defaultAudioDevice)")
        updateAudioDeviceState()
    }

    /** Changes selection of the currently active audio device.   */
    fun selectAudioDevice(device: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        if (!audioDevices.contains(device)) {
            Log.e(TAG, "Can not select \$device from available \$audioDevices")
        }
        userSelectedAudioDevice = device
        updateAudioDeviceState()
    }

    private fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter) {
        apprtcContext.registerReceiver(receiver, filter)
    }

    private fun unregisterReceiver(receiver: BroadcastReceiver?) {
        apprtcContext.unregisterReceiver(receiver)
    }

    private inner class WiredHeadsetReceiver : BroadcastReceiver() {
        private val STATE_UNPLUGGED = 0
        private val STATE_PLUGGED = 1
        private val HAS_NO_MIC = 0
        private val HAS_MIC = 1

        override fun onReceive(p0: Context, p1: Intent) {
            var state = p1.getIntExtra("state", STATE_UNPLUGGED)
            var microphone = p1.getIntExtra("microphone", HAS_NO_MIC)
            var name = p1.getStringExtra("name")
            hasWiredHeadset = (state == STATE_PLUGGED)
            updateAudioDeviceState()
        }
    }

    private fun hasEarpiece(): Boolean {
        return apprtcContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    private fun hasWiredHeadset(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (device in devices) {
            val type = device.type
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                Log.d(TAG, "hasWiredHeadset: found wired headset")
                return true
            } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                Log.d(TAG, "hasWiredHeadset: found USB audio device")
                return true
            }
        }
        return false
    }

    private fun setAudioDeviceInternal(device: AudioDevice?) {
        Log.d(TAG, String.format("setAudioDeviceInternal(device=%s)", device))
        if (audioDevices.contains(device)) {
            if (AudioDevice.SPEAKER_PHONE == device) {
                setSpeakerphoneOn(true)
            } else if (AudioDevice.EARPIECE == device) {
                setSpeakerphoneOn(false)
            } else if (AudioDevice.WIRED_HEADSET == device) {
                setSpeakerphoneOn(false)
            } else {
                Log.e(TAG, "Invalid audio device selection")
            }
        }
        selectedAudioDevice = device
    }

    private fun setSpeakerphoneOn(on: Boolean) {
        val wasOn = audioManager.isSpeakerphoneOn
        if (wasOn == on) {
            return
        }
        audioManager.isSpeakerphoneOn = on
    }

    private fun setMicrophoneMute(on: Boolean) {
        val wasMuted = audioManager.isMicrophoneMute
        if (wasMuted == on) {
            return
        }
        audioManager.isMicrophoneMute = on
    }

    private fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()

        val newAudioDevices: MutableSet<AudioDevice> = HashSet()

        if (hasWiredHeadset) {
            newAudioDevices.add(AudioDevice.WIRED_HEADSET)
        } else {
            newAudioDevices.add(AudioDevice.SPEAKER_PHONE)
            if (hasEarpiece()) {
                newAudioDevices.add(AudioDevice.EARPIECE)
            }
        }

        var audioDeviceSetUpdated = audioDevices != newAudioDevices
        audioDevices = newAudioDevices
        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET
        }
        if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE
        }

        var newAudioDevice: AudioDevice? = null
        if (hasWiredHeadset) {
            newAudioDevice = AudioDevice.WIRED_HEADSET
        } else {
            newAudioDevice = defaultAudioDevice
        }

        if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
            setAudioDeviceInternal(newAudioDevice)
            audioManagerEvents?.onAudioDeviceChanged(selectedAudioDevice, audioDevices)
        }
    }

    companion object {
        private const val TAG = "AppRTCAudioManager"
        private const val SPEAKERPHONE_AUTO = "auto"
        private const val SPEAKERPHONE_TRUE = "true"
        private const val SPEAKERPHONE_FALSE = "false"
    }
}