package me.amryousef.webrtc_demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import io.ktor.util.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import me.amryousef.webrtc_demo.databinding.ActivityMainBinding
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import org.webrtc.voiceengine.WebRtcAudioUtils

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class MainActivity : AppCompatActivity() {

    private val bind: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    companion object {
        private const val CAMERA_AUDIO_PERMISSION_REQUEST_CODE = 1
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO
    }
    private lateinit var audioManager: RTCAudioManager

    private lateinit var rtcClient: RTCClient
    private lateinit var signallingClient: SignallingClient

    private var isJoin = false
    private var isMute = false
    private var isVideoPaused = false
    private var inSpeakerMode = true

    private val sdpObserver = object : AppSdpObserver() {
        override fun onCreateSuccess(p0: SessionDescription?) {
            super.onCreateSuccess(p0)
            signallingClient.send(p0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(bind.root)

        audioManager = RTCAudioManager(applicationContext)
        checkCameraPermission()

        audioManager.setDefaultAudioDevice(RTCAudioManager.AudioDevice.SPEAKER_PHONE)
        audioManager.selectAudioDevice(RTCAudioManager.AudioDevice.SPEAKER_PHONE)


//        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true)
//        WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true)
//        WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true)
        WebRtcAudioUtils.useWebRtcBasedAcousticEchoCanceler()
        WebRtcAudioUtils.useWebRtcBasedAutomaticGainControl()
        WebRtcAudioUtils.useWebRtcBasedNoiseSuppressor()

        bind.btnMic.setOnClickListener {
            if (isMute) {
                isMute = false
                bind.btnMic.setImageResource(R.drawable.ic_baseline_mic_off_24)
            } else {
                isMute = true
                bind.btnMic.setImageResource(R.drawable.ic_baseline_mic_24)
            }
            rtcClient.enableAudio(isMute)
        }

        bind.btnSpeaker.setOnClickListener {
            if (inSpeakerMode) {
                inSpeakerMode = false
                audioManager.setDefaultAudioDevice(RTCAudioManager.AudioDevice.EARPIECE)
                bind.btnSpeaker.setImageResource(R.drawable.ic_baseline_hearing_24)
            } else {
                inSpeakerMode= true
                audioManager.setDefaultAudioDevice(RTCAudioManager.AudioDevice.SPEAKER_PHONE)
                bind.btnSpeaker.setImageResource(R.drawable.ic_baseline_speaker_up_24)
            }
        }
    }

    private fun checkCameraPermission() {
        if ((ContextCompat.checkSelfPermission(
                this,
                CAMERA_PERMISSION
            ) != PackageManager.PERMISSION_GRANTED)
            && (ContextCompat.checkSelfPermission(
                this,
                AUDIO_PERMISSION
            ) != PackageManager.PERMISSION_GRANTED)
        ) {
            requestCameraPermission()
        } else {
            onCameraPermissionGranted()
        }
    }

    private fun onCameraPermissionGranted() {
        rtcClient = RTCClient(
            application,
            object : PeerConnectionObserver() {
                override fun onIceCandidate(p0: IceCandidate?) {
                    super.onIceCandidate(p0)
                    signallingClient.send(p0)
                    rtcClient.addIceCandidate(p0)
                }

                override fun onAddStream(p0: MediaStream?) {
                    super.onAddStream(p0)
                    p0?.videoTracks?.get(0)?.addSink(bind.remoteView)
                }
            }
        )
        rtcClient.initSurfaceView(bind.remoteView)
        rtcClient.initSurfaceView(bind.localView)
        rtcClient.startLocalVideoCapture(bind.localView)
        signallingClient = SignallingClient(createSignallingClientListener())
        call_button.setOnClickListener { rtcClient.call(sdpObserver) }
    }

    private fun createSignallingClientListener() = object : SignallingClientListener {
        override fun onConnectionEstablished() {
            call_button.isClickable = true
        }

        override fun onOfferReceived(description: SessionDescription) {
            rtcClient.onRemoteSessionReceived(description)
            rtcClient.answer(sdpObserver)
            bind.remoteViewLoading.isGone = true
        }

        override fun onAnswerReceived(description: SessionDescription) {
            rtcClient.onRemoteSessionReceived(description)
            bind.remoteViewLoading.isGone = true
        }

        override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
            rtcClient.addIceCandidate(iceCandidate)
        }
    }

    private fun requestCameraPermission(dialogShown: Boolean = false) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, CAMERA_PERMISSION) &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                AUDIO_PERMISSION
            ) && !dialogShown
        ) {
            showPermissionRationaleDialog()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(CAMERA_PERMISSION, AUDIO_PERMISSION), CAMERA_AUDIO_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera And Audio Permission Required")
            .setMessage("This app need the camera and audio to function")
            .setPositiveButton("Grant") { dialogInterface, i ->
                dialogInterface.dismiss()
                requestCameraPermission(true)
            }
            .setNegativeButton(
                "Deny"
            ) { dialogInterface, i ->
                dialogInterface.dismiss()
                onCameraPermissionDenied()
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_AUDIO_PERMISSION_REQUEST_CODE) {
            for (grantResult in grantResults) {
                if (PackageManager.PERMISSION_GRANTED != grantResult) {
                    onCameraPermissionDenied()
                    break
                }
                onCameraPermissionGranted()
            }
        } else {
            onCameraPermissionDenied()
        }
    }

    private fun onCameraPermissionDenied() {
        Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        signallingClient.destroy()
        super.onDestroy()
    }
}
