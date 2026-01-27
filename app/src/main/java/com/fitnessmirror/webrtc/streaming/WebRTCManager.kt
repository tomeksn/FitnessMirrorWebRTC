package com.fitnessmirror.webrtc.streaming

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
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
     * Filter VP8 codec from SDP to force H.264 usage
     * H.264 has better hardware support on TVs
     */
    private fun filterVp8FromSdp(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val filteredLines = mutableListOf<String>()
        var vp8PayloadType: String? = null

        // First pass: find VP8 payload type
        for (line in lines) {
            if (line.contains("a=rtpmap:") && line.contains("VP8/90000")) {
                // Extract payload type (e.g., "a=rtpmap:96 VP8/90000" -> "96")
                val match = Regex("a=rtpmap:(\\d+) VP8").find(line)
                vp8PayloadType = match?.groupValues?.get(1)
                Log.d(TAG, "Found VP8 payload type: $vp8PayloadType")
                break
            }
        }

        if (vp8PayloadType == null) {
            Log.d(TAG, "No VP8 codec found in SDP, returning unchanged")
            return sdp
        }

        // Second pass: filter out VP8 lines
        for (line in lines) {
            val skipLine = (
                line.contains("a=rtpmap:$vp8PayloadType ") ||
                line.contains("a=rtcp-fb:$vp8PayloadType ") ||
                line.contains("a=fmtp:$vp8PayloadType ")
            )

            if (!skipLine) {
                // Also remove VP8 from m= line payload list
                if (line.startsWith("m=video") && vp8PayloadType != null) {
                    val filtered = line.replace(" $vp8PayloadType", "")
                    filteredLines.add(filtered)
                } else {
                    filteredLines.add(line)
                }
            }
        }

        val result = filteredLines.joinToString("\r\n")
        Log.d(TAG, "SDP filtered - removed VP8 codec, forcing H.264")
        return result
    }

    /**
     * Initialize video source for manual frame injection
     * Camera frames will be provided by CameraManager
     */
    private fun initializeVideoSource() {
        Log.d(TAG, "Initializing video source (without capturer - uses CameraManager)")

        try {
            // Create video source WITHOUT capturer
            // We'll feed frames manually from CameraManager
            localVideoSource = peerConnectionFactory?.createVideoSource(false)

            // Create video track
            videoTrack = peerConnectionFactory?.createVideoTrack("video_track", localVideoSource)
            videoTrack?.setEnabled(true)

            Log.d(TAG, "Video source initialized successfully (manual frame injection mode)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video source", e)
            callback.onError("Video source initialization failed: ${e.message}")
            throw e
        }
    }

    /**
     * Inject video frame from CameraManager
     * Called by StreamingService when new frame is available
     *
     * @param image ImageProxy from CameraX ImageAnalysis
     */
    fun injectFrame(image: ImageProxy) {
        try {
            Log.d(TAG, "🔍 injectFrame called - width=${image.width}, height=${image.height}, format=${image.format}")

            // Check API availability
            Log.d(TAG, "VideoSource: $localVideoSource")
            Log.d(TAG, "CapturerObserver accessible: ${try { localVideoSource?.capturerObserver != null } catch (e: Exception) { "ERROR: $e" }}")

            // Try to detect available buffer classes
            val availableBuffers = detectAvailableBufferClasses()
            Log.d(TAG, "Available buffer classes: $availableBuffers")

            // Convert ImageProxy (YUV) to WebRTC VideoFrame
            val videoFrame = imageProxyToVideoFrame(image)

            if (videoFrame != null) {
                Log.d(TAG, "✅ VideoFrame created successfully")
                // Feed frame to video source
                localVideoSource?.capturerObserver?.onFrameCaptured(videoFrame)
                Log.d(TAG, "✅ Frame injected to capturerObserver")
                videoFrame.release()
            } else {
                Log.e(TAG, "❌ VideoFrame creation failed - returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to inject frame", e)
            e.printStackTrace()
        }
    }

    private fun detectAvailableBufferClasses(): String {
        val classes = mutableListOf<String>()
        try { JavaI420Buffer.allocate(1, 1); classes.add("JavaI420Buffer") } catch (e: Exception) { }
        try { Class.forName("org.webrtc.I420Buffer"); classes.add("I420Buffer") } catch (e: Exception) { }
        try { Class.forName("org.webrtc.NV21Buffer"); classes.add("NV21Buffer") } catch (e: Exception) { }
        try { Class.forName("org.webrtc.NV12Buffer"); classes.add("NV12Buffer") } catch (e: Exception) { }
        return if (classes.isEmpty()) "NONE FOUND" else classes.joinToString(", ")
    }

    /**
     * Convert CameraX ImageProxy (YUV) to WebRTC VideoFrame
     * Properly handles rowStride and pixelStride to avoid BufferOverflowException
     */
    private fun imageProxyToVideoFrame(image: ImageProxy): VideoFrame? {
        try {
            val width = image.width
            val height = image.height

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            Log.d(TAG, "📊 Y plane: rowStride=${yPlane.rowStride}, pixelStride=${yPlane.pixelStride}, buffer=${yPlane.buffer.remaining()}")
            Log.d(TAG, "📊 U plane: rowStride=${uPlane.rowStride}, pixelStride=${uPlane.pixelStride}, buffer=${uPlane.buffer.remaining()}")
            Log.d(TAG, "📊 V plane: rowStride=${vPlane.rowStride}, pixelStride=${vPlane.pixelStride}, buffer=${vPlane.buffer.remaining()}")

            // Create I420 buffer
            val i420Buffer = JavaI420Buffer.allocate(width, height)

            // Copy Y plane (handle rowStride - may have padding)
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yData = i420Buffer.dataY

            yData.position(0)
            if (yRowStride == width) {
                // No padding, can copy directly
                yBuffer.position(0)
                yData.put(yBuffer)
            } else {
                // Has padding, copy line by line
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.limit(row * yRowStride + width)
                    yData.put(yBuffer)
                }
            }

            // Copy U plane (handle rowStride and pixelStride)
            val uBuffer = uPlane.buffer
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            val uData = i420Buffer.dataU

            uData.position(0)
            if (uPixelStride == 1 && uRowStride == chromaWidth) {
                // Planar format without padding
                uBuffer.position(0)
                uData.put(uBuffer)
            } else if (uPixelStride == 1) {
                // Planar format with padding
                for (row in 0 until chromaHeight) {
                    uBuffer.position(row * uRowStride)
                    uBuffer.limit(row * uRowStride + chromaWidth)
                    uData.put(uBuffer)
                }
            } else {
                // Semi-planar or interleaved format
                for (row in 0 until chromaHeight) {
                    for (col in 0 until chromaWidth) {
                        val pos = row * uRowStride + col * uPixelStride
                        uData.put(uBuffer.get(pos))
                    }
                }
            }

            // Copy V plane (handle rowStride and pixelStride)
            val vBuffer = vPlane.buffer
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride
            val vData = i420Buffer.dataV

            vData.position(0)
            if (vPixelStride == 1 && vRowStride == chromaWidth) {
                // Planar format without padding
                vBuffer.position(0)
                vData.put(vBuffer)
            } else if (vPixelStride == 1) {
                // Planar format with padding
                for (row in 0 until chromaHeight) {
                    vBuffer.position(row * vRowStride)
                    vBuffer.limit(row * vRowStride + chromaWidth)
                    vData.put(vBuffer)
                }
            } else {
                // Semi-planar or interleaved format
                for (row in 0 until chromaHeight) {
                    for (col in 0 until chromaWidth) {
                        val pos = row * vRowStride + col * vPixelStride
                        vData.put(vBuffer.get(pos))
                    }
                }
            }

            // Create VideoFrame with timestamp
            val timestampNs = System.nanoTime()
            return VideoFrame(i420Buffer, 0 /* rotation */, timestampNs)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert ImageProxy to VideoFrame", e)
            return null
        }
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

                        // Filter VP8 from SDP - only for sending to TV, not for local description
                        val filteredSdpString = filterVp8FromSdp(sdp.description)
                        val filteredSdp = SessionDescription(sdp.type, filteredSdpString)
                        Log.d(TAG, "SDP filtered to prefer H.264 over VP8 (for TV)")

                        // Set ORIGINAL SDP as local description (WebRTC needs all available codecs)
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Local description set successfully")
                                // Send FILTERED SDP to TV (without VP8)
                                callback.onLocalDescription(filteredSdp)
                            }

                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "Failed to set local description: $error")
                                callback.onError("Failed to set local description: $error")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, sdp)  // Use ORIGINAL SDP for local description
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
     * Check if front camera is currently active
     */
    fun isFrontCamera(): Boolean = isFrontCamera

    /**
     * Cleanup and release all WebRTC resources
     */
    fun close() {
        Log.d(TAG, "Closing WebRTC manager")

        try {
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
