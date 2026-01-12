package com.fitnessmirror.webrtc.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.camera.core.ImageProxy
import com.fitnessmirror.webrtc.MainActivity
import com.fitnessmirror.webrtc.R
import com.fitnessmirror.webrtc.camera.CameraManager
import com.fitnessmirror.webrtc.camera.CameraMode
import com.fitnessmirror.webrtc.network.NetworkUtils
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class StreamingService : Service(), LifecycleOwner, CameraManager.CameraCallback, StreamingServer.StreamingCallback, WebRTCManager.WebRTCCallback {

    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "streaming_channel"
        private const val SERVER_PORT = 8080

        // Service actions
        const val ACTION_START_STREAMING = "com.fitnessmirror.webrtc.START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.fitnessmirror.webrtc.STOP_STREAMING"
        const val ACTION_SWITCH_CAMERA = "com.fitnessmirror.webrtc.SWITCH_CAMERA"

        // Static service instance tracking
        @Volatile
        private var currentInstance: StreamingService? = null
        private var instanceCount = 0

        fun startStreamingService(context: Context) {
            // Force cleanup of any existing instance first
            currentInstance?.let { existingInstance ->
                Log.w(TAG, "Cleaning up existing service instance before starting new one")
                try {
                    existingInstance.forceCleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during forced cleanup", e)
                }
            }

            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_START_STREAMING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopStreamingService(context: Context) {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_STOP_STREAMING
            }
            context.startService(intent)
        }
    }

    // Service components
    private lateinit var cameraManager: CameraManager
    private lateinit var streamingServer: StreamingServer
    private var notificationManager: NotificationManager? = null
    private var webRTCManager: WebRTCManager? = null  // WebRTC peer connection manager
    private var useWebRTC: Boolean = true  // Try WebRTC first, fallback to WebSocket if fails

    // Streaming state
    private var isStreaming = false
    private var hasConnectedClient = false
    private var serverAddress: String? = null
    private var isCameraReady = false  // FIX #3/#4: Track camera initialization

    // Background streaming wake lock
    private var wakeLock: PowerManager.WakeLock? = null

    // Lifecycle management
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    // Service callback interface
    interface StreamingServiceCallback {
        fun onStreamingStateChanged(isStreaming: Boolean, hasConnectedClient: Boolean, serverAddress: String?)
        fun onFrameReady(jpegData: ByteArray)
        fun onCameraSwitched()
    }

    private var serviceCallback: StreamingServiceCallback? = null

    // Binder for activity communication
    inner class StreamingBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService

        fun setCallback(callback: StreamingServiceCallback) {
            serviceCallback = callback
        }

        fun isStreaming(): Boolean = this@StreamingService.isStreaming
        fun hasConnectedClient(): Boolean = this@StreamingService.hasConnectedClient
        fun getServerAddress(): String? = this@StreamingService.serverAddress

        fun switchCamera() {
            this@StreamingService.switchCamera()
        }

        fun transferYouTubeToTV(videoId: String, currentTime: Float = 0f) {
            this@StreamingService.transferYouTubeToTV(videoId, currentTime)
        }

        fun returnYouTubeToPhone() {
            this@StreamingService.returnYouTubeToPhone()
        }
    }

    private val binder = StreamingBinder()

    override fun onCreate() {
        super.onCreate()
        instanceCount++
        currentInstance = this
        Log.d(TAG, "StreamingService created (instance #$instanceCount)")

        // Initialize lifecycle
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // Initialize notification manager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Initialize components
        try {
            cameraManager = CameraManager(this as Context, this as LifecycleOwner)
            streamingServer = StreamingServer(SERVER_PORT, this, this as Context)

            // StreamingService always uses STREAMING mode (JPEG processing enabled)
            cameraManager.initialize(this, CameraMode.STREAMING)
            Log.d(TAG, "Background streaming components initialized in STREAMING mode")

            // Try to initialize WebRTC for low-latency streaming
            try {
                webRTCManager = WebRTCManager(this as Context, this)
                webRTCManager?.initialize()
                useWebRTC = true
                Log.i(TAG, "✅ WebRTC initialized successfully - will use WebRTC streaming")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ WebRTC initialization failed, will use WebSocket fallback", e)
                webRTCManager = null
                useWebRTC = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize background streaming components", e)
        }

        // Move to started state
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAMING -> {
                Log.d(TAG, "Starting background streaming")
                startForegroundStreaming()
            }
            ACTION_STOP_STREAMING -> {
                Log.d(TAG, "Stopping background streaming")
                stopForegroundStreaming()
            }
            ACTION_SWITCH_CAMERA -> {
                Log.d(TAG, "Switching camera in background")
                switchCamera()
            }
        }

        // Prevent automatic restart to avoid port conflicts
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun startForegroundStreaming() {
        try {
            // Check port availability first
            if (!isPortAvailable(SERVER_PORT)) {
                Log.e(TAG, "Port $SERVER_PORT is already in use - attempting to free it")
                // Try to free the port by stopping any existing server
                try {
                    if (::streamingServer.isInitialized) {
                        streamingServer.stopServer()
                    }
                    // Wait a moment for port to be released
                    Thread.sleep(500)
                } catch (e: Exception) {
                    Log.w(TAG, "Error while trying to free port", e)
                }

                // Check again after cleanup attempt
                if (!isPortAvailable(SERVER_PORT)) {
                    Log.e(TAG, "Port $SERVER_PORT still not available after cleanup attempt")
                    serviceCallback?.onStreamingStateChanged(false, false, null)
                    stopSelf()
                    return
                }
            }

            // Start foreground service with notification
            startForeground(NOTIFICATION_ID, createStreamingNotification())

            // Acquire enhanced wake lock for background streaming
            acquireWakeLock()

            // FIX #3/#4: Start camera FIRST - server starts when ready
            val localIp = NetworkUtils.getLocalIpAddress(this)
            if (localIp != null) {
                serverAddress = "$localIp:$SERVER_PORT"

                // Start camera first - server will start in onCameraFullyInitialized() callback
                Log.d(TAG, "🎥 Starting camera first - server will start after camera ready")
                cameraManager.startBackgroundStreaming()
                isStreaming = true

                // Update notification to show initialization
                updateNotification("Initializing camera... - $serverAddress")

                Log.d(TAG, "Waiting for camera initialization callback...")
                // onCameraFullyInitialized() will be called when camera is ready

            } else {
                Log.e(TAG, "Could not get local IP address for background streaming")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start background streaming", e)
            stopSelf()
        }
    }

    private fun stopForegroundStreaming() {
        try {
            // Stop streaming components
            if (::streamingServer.isInitialized) {
                streamingServer.stopServer()
            }
            if (::cameraManager.isInitialized) {
                cameraManager.stopStreaming()
            }

            // Release wake lock
            releaseWakeLock()

            // Reset state
            isStreaming = false
            hasConnectedClient = false
            serverAddress = null

            // Notify callback
            serviceCallback?.onStreamingStateChanged(isStreaming, hasConnectedClient, serverAddress)

            // Stop foreground service
            stopForeground(true)
            stopSelf()

            Log.d(TAG, "Background streaming stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background streaming", e)
        }
    }

    fun switchCamera() {
        if (::cameraManager.isInitialized && isStreaming) {
            cameraManager.switchCamera()
            updateNotification("Camera switched - $serverAddress")

            // Notify callback about camera switch
            serviceCallback?.onCameraSwitched()
            Log.d(TAG, "Camera switched in streaming service - callback notified")
        }
    }

    fun transferYouTubeToTV(videoId: String, currentTime: Float = 0f) {
        if (::streamingServer.isInitialized && hasConnectedClient) {
            try {
                Log.d(TAG, "Transferring YouTube video $videoId to TV at ${currentTime}s")

                // Send YouTube video info to TV via WebSocket
                streamingServer.sendYouTubeVideoInfo(videoId, currentTime)

                updateNotification("YouTube transferred to TV")
                Log.d(TAG, "✅ YouTube video info sent to TV successfully: $videoId at ${currentTime}s")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error transferring YouTube to TV", e)
                updateNotification("Failed to transfer YouTube to TV")
            }
        } else {
            Log.w(TAG, "Cannot transfer YouTube: server not initialized (${::streamingServer.isInitialized}) or no client ($hasConnectedClient)")
        }
    }

    fun returnYouTubeToPhone() {
        if (::streamingServer.isInitialized && hasConnectedClient) {
            try {
                Log.d(TAG, "Returning YouTube from TV to phone")

                // Send stop command to TV via WebSocket
                streamingServer.sendVideoCommand("stop")

                updateNotification("YouTube returned to phone")
                Log.d(TAG, "✅ Stop command sent to TV successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error returning YouTube from TV", e)
                updateNotification("Failed to return YouTube to phone")
            }
        } else {
            Log.w(TAG, "Cannot return YouTube: server not initialized (${::streamingServer.isInitialized}) or no client ($hasConnectedClient)")
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "FitnessMirror::BackgroundStreamingWakeLock"
                )
            }

            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(3600000L) // 1 hour max
                Log.d(TAG, "Background streaming wake lock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire background wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Background streaming wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release background wake lock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Fitness Mirror Streaming",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Camera streaming to TV in background"
                    setShowBadge(false)
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                }
                notificationManager?.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create notification channel", e)
            }
        }
    }

    private fun createStreamingNotification(): Notification {
        return try {
            val intent = Intent(this as Context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this as Context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            NotificationCompat.Builder(this as Context, CHANNEL_ID)
                .setContentTitle("Fitness Mirror Streaming")
                .setContentText("Camera streaming active in background")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSilent(true)
                .setShowWhen(false)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification, using simple notification", e)
            // Fallback minimal notification
            NotificationCompat.Builder(this as Context, CHANNEL_ID)
                .setContentTitle("Streaming Active")
                .setContentText("Background streaming")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        try {
            val notification = NotificationCompat.Builder(this as Context, CHANNEL_ID)
                .setContentTitle("Fitness Mirror Streaming")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setShowWhen(false)
                .build()

            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "StreamingService destroyed (instance #$instanceCount)")

        // Clear current instance if it's this instance
        if (currentInstance == this) {
            currentInstance = null
        }

        // Destroy lifecycle
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        // Cleanup
        forceCleanup()
    }

    private fun forceCleanup() {
        try {
            Log.d(TAG, "Performing forced cleanup of streaming components")

            // Stop streaming server first to release port
            if (::streamingServer.isInitialized) {
                streamingServer.stopServer()
                Log.d(TAG, "Streaming server stopped during cleanup")
            }

            // Release wake lock
            releaseWakeLock()

            // Cleanup camera
            if (::cameraManager.isInitialized) {
                cameraManager.cleanup()
                Log.d(TAG, "Camera manager cleaned up")
            }

            // Reset state
            isStreaming = false
            hasConnectedClient = false
            serverAddress = null

            Log.d(TAG, "Forced cleanup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during forced cleanup", e)
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            val socket = java.net.ServerSocket(port)
            socket.close()
            Log.d(TAG, "Port $port is available")
            true
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Port $port is not available: ${e.message}")
            false
        }
    }

    // CameraManager.CameraCallback implementation
    override fun onFrameReady(jpegData: ByteArray) {
        // FIX #3/#4: Only forward frames if camera is fully ready
        if (::streamingServer.isInitialized && isCameraReady) {
            streamingServer.broadcastFrame(jpegData)
        }

        // Notify activity if connected
        serviceCallback?.onFrameReady(jpegData)
    }

    override fun onRawFrameReady(image: ImageProxy) {
        // Don't process frames until camera is fully initialized
        if (!isCameraReady) {
            image.close()
            return
        }

        // Feed frame to WebRTC if initialized
        webRTCManager?.injectFrame(image)
    }

    // Unified onError implementation for all interfaces (CameraCallback, StreamingCallback, WebRTCCallback)
    override fun onError(error: String) {
        Log.e(TAG, "Error occurred: $error")

        // Determine error source and handle appropriately
        when {
            error.contains("camera", ignoreCase = true) -> {
                Log.e(TAG, "Camera error: $error")
                updateNotification("Camera error")
            }
            error.contains("webrtc", ignoreCase = true) || error.contains("peer", ignoreCase = true) -> {
                Log.e(TAG, "WebRTC error: $error")
                updateNotification("WebRTC error - using WebSocket fallback")
                fallbackToWebSocket()
            }
            else -> {
                Log.e(TAG, "Streaming error: $error")
                updateNotification("Streaming error")
            }
        }
    }

    override fun onPreviewReady(preview: androidx.camera.core.Preview) {
        // Not needed for background streaming
        Log.d(TAG, "Preview ready in background mode")
    }

    override fun onPreviewChanged(preview: androidx.camera.core.Preview) {
        // Not needed for background streaming
        Log.d(TAG, "Preview changed in background mode")
    }

    override fun onPreviewSurfaceConflict() {
        Log.w(TAG, "Surface conflict in background streaming")
        updateNotification("Camera conflict - resolving...")
    }

    override fun onCameraFullyInitialized() {
        // FIX #3/#4: Camera is ready - NOW start the server
        Log.d(TAG, "✅ Camera fully initialized - NOW starting server")
        isCameraReady = true

        try {
            // Now start the server (camera is ready for frames)
            streamingServer.startServer()

            // Update notification
            updateNotification("Streaming active - $serverAddress")

            // Notify UI with complete state
            serviceCallback?.onStreamingStateChanged(isStreaming, hasConnectedClient, serverAddress)

            Log.d(TAG, "✅ Full stack ready: Camera → Server → Ready for TV")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server after camera ready", e)
            stopSelf()
        }
    }

    // StreamingServer.StreamingCallback implementation
    override fun onClientConnected() {
        hasConnectedClient = true
        updateNotification("Client connected - streaming active")
        serviceCallback?.onStreamingStateChanged(isStreaming, hasConnectedClient, serverAddress)
        Log.d(TAG, "Background streaming client connected")

        // Create WebRTC offer when client connects
        webRTCManager?.let {
            Log.d(TAG, "📡 Client connected - creating WebRTC offer")
            it.createOffer()
        }
    }

    override fun onClientDisconnected() {
        hasConnectedClient = false
        updateNotification("No clients - waiting for connection")
        serviceCallback?.onStreamingStateChanged(isStreaming, hasConnectedClient, serverAddress)
        Log.d(TAG, "Background streaming client disconnected")
    }

    override fun onServerError(error: String) {
        Log.e(TAG, "Background streaming server error: $error")
        updateNotification("Server error - $error")
    }

    override fun onServerStarted(port: Int) {
        Log.d(TAG, "Background streaming server started on port $port")
        updateNotification("Server started on port $port")
    }

    // WebRTC signaling callbacks from StreamingServer
    override fun onWebRTCOffer(sdp: SessionDescription) {
        Log.d(TAG, "Received WebRTC offer from TV client")
        // TV is offering to receive our video - we'll create answer
        webRTCManager?.setRemoteDescription(sdp)
    }

    override fun onWebRTCAnswer(sdp: SessionDescription) {
        Log.d(TAG, "Received WebRTC answer from TV client")
        // TV accepted our offer - set remote description
        webRTCManager?.setRemoteDescription(sdp)
    }

    override fun onWebRTCIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "Received ICE candidate from TV client")
        // Add ICE candidate to establish connection
        webRTCManager?.addIceCandidate(candidate)
    }

    // WebRTCManager.WebRTCCallback implementation
    override fun onLocalDescription(sdp: SessionDescription) {
        Log.d(TAG, "Local SDP created: ${sdp.type.canonicalForm()}")
        // Send our SDP to TV client via server
        streamingServer.sendSdpToClient(sdp)
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "Local ICE candidate generated")
        // Send ICE candidate to TV client via server
        streamingServer.sendICECandidateToClient(candidate)
    }

    override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
        Log.d(TAG, "WebRTC connection state: $state")
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                Log.i(TAG, "✅ WebRTC connection established!")
                updateNotification("WebRTC Connected - Low latency streaming")
                hasConnectedClient = true
                serviceCallback?.onStreamingStateChanged(isStreaming, hasConnectedClient, serverAddress)
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                Log.w(TAG, "WebRTC disconnected")
                hasConnectedClient = false
                serviceCallback?.onStreamingStateChanged(isStreaming, hasConnectedClient, serverAddress)
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                Log.e(TAG, "❌ WebRTC connection failed - falling back to WebSocket")
                fallbackToWebSocket()
            }
            else -> {
                Log.d(TAG, "WebRTC state: $state")
            }
        }
    }

    /**
     * Fallback to WebSocket streaming when WebRTC fails
     */
    private fun fallbackToWebSocket() {
        if (!useWebRTC) {
            Log.d(TAG, "Already using WebSocket, ignoring fallback request")
            return
        }

        Log.i(TAG, "⚠️ Falling back to WebSocket streaming...")

        // Cleanup WebRTC
        webRTCManager?.close()
        webRTCManager = null
        useWebRTC = false

        // WebSocket streaming is already initialized, just update notification
        updateNotification("Using WebSocket fallback - $serverAddress")
        Log.i(TAG, "✅ WebSocket fallback active")
    }
}