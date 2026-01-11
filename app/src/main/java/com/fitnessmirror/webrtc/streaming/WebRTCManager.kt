package com.fitnessmirror.webrtc.streaming

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.PeerConnection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WebRTCManager - Manages WebRTC peer connection for low-latency video streaming
 *
 * This class handles:
 * - PeerConnectionFactory initialization
 * - Video track creation from camera
 * - SDP offer/answer negotiation
 * - ICE candidate exchange
 * - Camera switching (front/back)
 * - Connection lifecycle management
 *
 * Target latency: 100-300ms (compared to 750ms with WebSocket JPEG)
 */
class WebRTCManager(
    private val context: Context,
    private val callback: WebRTCCallback
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val VIDEO_WIDTH = 320
        private const val VIDEO_HEIGHT = 240
        private const val VIDEO_FPS = 30
    }

    interface WebRTCCallback {
        fun onLocalDescription(sdp: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChange(state: PeerConnectionState)
        fun onError(error: String)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null

    private var isFrontCamera = true
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    /**
     * Initialize WebRTC components
     * Must be called before any other operations
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC components")

        try {
            // Initialize PeerConnectionFactory
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .setFieldTrials("")
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initializationOptions)

            // Create PeerConnectionFactory
            val options = PeerConnectionFactory.Options()
            val encoderFactory = DefaultVideoEncoderFactory(
                EglBase.create().eglBaseContext,
                true,  // Enable Intel VP8 encoder
                true   // Enable H264 high profile
            )
            val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            Log.d(TAG, "PeerConnectionFactory created successfully")

            // Initialize camera video source
            initializeVideoSource()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC", e)
            callback.onError("WebRTC initialization failed: ${e.message}")
            throw e
        }
    }

    /**
     * Initialize video source from camera
     */
    private fun initializeVideoSource() {
        Log.d(TAG, "Initializing video source")

        try {
            // Create video source
            localVideoSource = peerConnectionFactory?.createVideoSource(false)

            // Create video capturer (front camera by default)
            videoCapturer = createCameraCapturer(true)

            if (videoCapturer == null) {
                throw IllegalStateException("Failed to create camera capturer")
            }

            // Create surface texture helper
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                EglBase.create().eglBaseContext
            )

            // Initialize capturer
            videoCapturer?.initialize(
                surfaceTextureHelper,
                context,
                localVideoSource?.capturerObserver
            )

            // Start capturing
            videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

            // Create video track
            videoTrack = peerConnectionFactory?.createVideoTrack("video_track", localVideoSource)
            videoTrack?.setEnabled(true)

            Log.d(TAG, "Video source initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video source", e)
            callback.onError("Camera initialization failed: ${e.message}")
            throw e
        }
    }

    /**
     * Create camera capturer for specified camera facing
     */
    private fun createCameraCapturer(useFrontCamera: Boolean): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Try to find the requested camera
        for (deviceName in deviceNames) {
            if (useFrontCamera && enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Creating front camera capturer: $deviceName")
                return enumerator.createCapturer(deviceName, null)
            } else if (!useFrontCamera && enumerator.isBackFacing(deviceName)) {
                Log.d(TAG, "Creating back camera capturer: $deviceName")
                return enumerator.createCapturer(deviceName, null)
            }
        }

        Log.e(TAG, "Failed to find camera: ${if (useFrontCamera) "front" else "back"}")
        return null
    }

    /**
     * Create peer connection and initiate offer
     */
    fun createOffer() {
        Log.d(TAG, "Creating WebRTC offer")

        coroutineScope.launch {
            try {
                // Create peer connection if not exists
                if (peerConnection == null) {
                    createPeerConnection()
                }

                // Create offer constraints
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                }

                // Create offer
                peerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        Log.d(TAG, "Offer created successfully")
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Local description set successfully")
                                callback.onLocalDescription(sdp)
                            }

                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "Failed to set local description: $error")
                                callback.onError("Failed to set local description: $error")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, sdp)
                    }

                    override fun onSetSuccess() {}

                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "Failed to create offer: $error")
                        callback.onError("Failed to create offer: $error")
                    }

                    override fun onSetFailure(error: String?) {}
                }, constraints)

            } catch (e: Exception) {
                Log.e(TAG, "Error creating offer", e)
                callback.onError("Error creating offer: ${e.message}")
            }
        }
    }

    /**
     * Create and configure peer connection
     */
    private fun createPeerConnection() {
        Log.d(TAG, "Creating peer connection")

        // ICE servers configuration (using Google's public STUN server)
        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        // RTCConfiguration
        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        // Create peer connection
        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "ICE candidate generated: ${candidate.sdp}")
                    callback.onIceCandidate(candidate)
                }

                override fun onConnectionChange(newState: PeerConnectionState) {
                    Log.d(TAG, "Connection state changed: $newState")
                    callback.onConnectionStateChange(newState)
                }

                override fun onIceConnectionChange(newState: IceConnectionState) {
                    Log.d(TAG, "ICE connection state: $newState")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE connection receiving change: $receiving")
                }

                override fun onIceGatheringChange(newState: IceGatheringState) {
                    Log.d(TAG, "ICE gathering state: $newState")
                }

                override fun onAddStream(stream: MediaStream) {
                    Log.d(TAG, "Stream added: ${stream.id}")
                }

                override fun onRemoveStream(stream: MediaStream) {
                    Log.d(TAG, "Stream removed: ${stream.id}")
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    Log.d(TAG, "Data channel received: ${dataChannel.label()}")
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }

                override fun onSignalingChange(newState: SignalingState) {
                    Log.d(TAG, "Signaling state: $newState")
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                    Log.d(TAG, "ICE candidates removed: ${candidates.size}")
                }

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    Log.d(TAG, "Track added")
                }
            }
        )

        // Add video track to peer connection
        videoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("stream_id"))
            Log.d(TAG, "Video track added to peer connection")
        }
    }

    /**
     * Set remote SDP answer from TV client
     */
    fun setRemoteDescription(sdp: SessionDescription) {
        Log.d(TAG, "Setting remote description")

        coroutineScope.launch {
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set successfully")
                }

                override fun onSetFailure(error: String) {
                    Log.e(TAG, "Failed to set remote description: $error")
                    callback.onError("Failed to set remote description: $error")
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sdp)
        }
    }

    /**
     * Add ICE candidate received from TV client
     */
    fun addIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "Adding ICE candidate")

        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * Switch between front and back camera
     */
    fun switchCamera() {
        Log.d(TAG, "Switching camera")

        coroutineScope.launch {
            try {
                // Stop current capturer
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()

                // Toggle camera
                isFrontCamera = !isFrontCamera

                // Create new capturer
                videoCapturer = createCameraCapturer(isFrontCamera)

                if (videoCapturer == null) {
                    Log.e(TAG, "Failed to create new camera capturer")
                    callback.onError("Failed to switch camera")
                    return@launch
                }

                // Reinitialize capturer
                val surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    EglBase.create().eglBaseContext
                )

                videoCapturer?.initialize(
                    surfaceTextureHelper,
                    context,
                    localVideoSource?.capturerObserver
                )

                // Start capturing
                videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

                Log.d(TAG, "Camera switched successfully to ${if (isFrontCamera) "front" else "back"}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch camera", e)
                callback.onError("Failed to switch camera: ${e.message}")
            }
        }
    }

    /**
     * Check if front camera is currently active
     */
    fun isFrontCamera(): Boolean = isFrontCamera

    /**
     * Cleanup and release all WebRTC resources
     */
    fun close() {
        Log.d(TAG, "Closing WebRTC manager")

        try {
            // Stop and dispose capturer
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            // Dispose video track
            videoTrack?.dispose()
            videoTrack = null

            // Dispose video source
            localVideoSource?.dispose()
            localVideoSource = null

            // Close peer connection
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            // Dispose factory
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            Log.d(TAG, "WebRTC manager closed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebRTC manager", e)
        }
    }
}
