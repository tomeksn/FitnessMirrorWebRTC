package com.fitnessmirror.webrtc

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.fitnessmirror.webrtc.ui.screens.HomeScreen
import com.fitnessmirror.webrtc.ui.screens.WorkoutScreen
import com.fitnessmirror.webrtc.ui.theme.FitnessMirrorNativeTheme
import com.fitnessmirror.webrtc.camera.CameraManager
import com.fitnessmirror.webrtc.camera.CameraMode
import com.fitnessmirror.webrtc.streaming.StreamingService
import com.fitnessmirror.webrtc.network.NetworkUtils
import androidx.camera.core.Preview

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SERVER_PORT = 8080
    }

    // State management
    private var currentScreen by mutableStateOf<Screen>(Screen.Home)
    private var currentYouTubeUrl by mutableStateOf<String?>(null)
    private var hasCameraPermission by mutableStateOf(false)


    // UI-only camera manager for preview display (separate from StreamingService)
    private lateinit var previewCameraManager: CameraManager

    // Streaming state (managed by StreamingService)
    private var isStreaming by mutableStateOf(false)
    private var hasConnectedClient by mutableStateOf(false)
    private var serverAddress by mutableStateOf<String?>(null)

    // YouTube TV control state
    private var isYouTubeOnTV by mutableStateOf(false)
    private var currentVideoTime by mutableStateOf(0f)

    // Surface recreation trigger for camera PIP
    private var surfaceRecreationTrigger by mutableStateOf(0)

    // Camera state for mirror effect
    private var isFrontCamera by mutableStateOf(false)

    // Wake lock for background streaming
    private var wakeLock: PowerManager.WakeLock? = null

    // Background streaming service
    private var streamingService: StreamingService? = null
    private var isServiceBound = false

    // User preferences for streaming mode
    private var keepScreenOn by mutableStateOf(false)

    // Service connection for background streaming
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d(TAG, "Background streaming service connected")
            val binder = service as StreamingService.StreamingBinder
            streamingService = binder.getService()
            isServiceBound = true

            // FIX #1: Immediately query service state to update UI (fixes double-click bug)
            val currentIsStreaming = binder.isStreaming()
            val currentHasClient = binder.hasConnectedClient()
            val currentServerAddress = binder.getServerAddress()

            if (currentIsStreaming) {
                Log.d(TAG, "Service already streaming - updating UI state immediately")
                this@MainActivity.isStreaming = currentIsStreaming
                this@MainActivity.hasConnectedClient = currentHasClient
                this@MainActivity.serverAddress = currentServerAddress
            }

            // Set callback for service updates
            binder.setCallback(object : StreamingService.StreamingServiceCallback {
                override fun onStreamingStateChanged(isStreaming: Boolean, hasConnectedClient: Boolean, serverAddress: String?) {
                    val wasStreaming = this@MainActivity.isStreaming

                    // Update UI state from background service
                    this@MainActivity.isStreaming = isStreaming
                    this@MainActivity.hasConnectedClient = hasConnectedClient
                    this@MainActivity.serverAddress = serverAddress
                    Log.d(TAG, "Service state updated - streaming: $isStreaming, client: $hasConnectedClient")

                    // Handle camera coordination based on streaming state changes
                    if (!wasStreaming && isStreaming) {
                        // Streaming just started - ensure preview camera is stopped
                        if (::previewCameraManager.isInitialized) {
                            previewCameraManager.stopStreaming()
                            Log.d(TAG, "Stopped preview camera - streaming service active")
                        }
                    } else if (wasStreaming && !isStreaming) {
                        // Streaming just stopped - restart preview camera
                        if (::previewCameraManager.isInitialized) {
                            previewCameraManager.startStreaming()
                            Log.d(TAG, "Restarted preview camera - streaming service stopped")
                        }
                    }

                    // Auto-transfer YouTube when client connects
                    if (hasConnectedClient && isStreaming && currentScreen is Screen.Workout && !isYouTubeOnTV) {
                        transferYouTubeToTV()
                    }
                }

                override fun onFrameReady(jpegData: ByteArray) {
                    // Frame handled by service, no additional processing needed
                }

                override fun onCameraSwitched() {
                    // Update surface recreation trigger when streaming service switches camera
                    surfaceRecreationTrigger++
                    Log.d(TAG, "Service camera switch - surface recreation trigger: $surfaceRecreationTrigger")
                }
            })
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.d(TAG, "Background streaming service disconnected")
            streamingService = null
            isServiceBound = false
        }
    }

    // Permission handling
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
        if (isGranted) {
            Log.d(TAG, "Camera permission granted")
            // DISABLED: NativeCameraView handles camera directly, no need for previewCameraManager
            // initializeCameraComponents()
        } else {
            Log.e(TAG, "Camera permission denied")
        }
    }

    sealed class Screen {
        object Home : Screen()
        data class Workout(val youtubeUrl: String) : Screen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "FitnessMirror Native starting...")

        // Check camera permission
        checkCameraPermission()

        setContent {
            FitnessMirrorNativeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val screen = currentScreen) {
                        is Screen.Home -> {
                            HomeScreen(
                                onStartWorkout = { youtubeUrl ->
                                    currentYouTubeUrl = youtubeUrl
                                    currentScreen = Screen.Workout(youtubeUrl)
                                    Log.d(TAG, "Starting workout with URL: $youtubeUrl")
                                }
                            )
                        }
                        is Screen.Workout -> {
                            WorkoutScreen(
                                youtubeUrl = screen.youtubeUrl,
                                isStreaming = isStreaming,
                                serverAddress = serverAddress,
                                hasConnectedClient = hasConnectedClient,
                                isYouTubeOnTV = isYouTubeOnTV,
                                videoStartTime = if (isYouTubeOnTV) 0f else currentVideoTime,
                                cameraManager = if (::previewCameraManager.isInitialized) previewCameraManager else null,
                                surfaceRecreationTrigger = surfaceRecreationTrigger,
                                isFrontCamera = isFrontCamera,
                                hasCameraPermission = hasCameraPermission,
                                keepScreenOn = keepScreenOn,
                                onBack = {
                                    currentScreen = Screen.Home
                                    if (isStreaming) {
                                        stopStreaming()
                                    }
                                },
                                onStartStreaming = {
                                    startStreaming()
                                },
                                onStopStreaming = {
                                    stopStreaming()
                                },
                                onReturnYouTubeToPhone = {
                                    returnYouTubeToPhone()
                                },
                                onSwitchCamera = {
                                    switchCamera()
                                },
                                onVideoTimeUpdate = { time ->
                                    updateVideoTime(time)
                                },
                                onToggleKeepScreenOn = {
                                    toggleKeepScreenOn()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                hasCameraPermission = true
                Log.d(TAG, "Camera permission already granted")
                // DISABLED: NativeCameraView handles camera directly, no need for previewCameraManager
                // initializeCameraComponents()
            }
            else -> {
                Log.d(TAG, "Requesting camera permission")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun initializeCameraComponents() {
        try {
            // Initialize preview-only camera manager for UI display
            previewCameraManager = CameraManager(this, this)

            // ⚡ PERFORMANCE OPTIMIZATION: Initialize in PREVIEW_ONLY mode
            // This eliminates unnecessary JPEG processing and provides smooth 60fps PIP
            // like FitnessMirror (Expo) which only uses simple camera preview
            previewCameraManager.initialize(
                callback = object : CameraManager.CameraCallback {
                    override fun onFrameReady(jpegData: ByteArray) {
                        // UI camera manager doesn't need to handle frames in PREVIEW_ONLY mode
                        // StreamingService handles JPEG processing when actually streaming to TV
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "Preview camera error: $error")
                    }

                    override fun onPreviewReady(preview: Preview) {
                        Log.d(TAG, "Preview camera ready for UI display (PREVIEW_ONLY mode)")
                    }

                    override fun onPreviewChanged(preview: Preview) {
                        Log.d(TAG, "Preview camera changed")
                    }

                    override fun onPreviewSurfaceConflict() {
                        Log.w(TAG, "Preview camera surface conflict - attempting recovery")
                        if (::previewCameraManager.isInitialized) {
                            previewCameraManager.forcePreviewRefresh()
                        }
                    }

                    override fun onCameraFullyInitialized() {
                        Log.d(TAG, "Preview camera fully initialized (PREVIEW_ONLY mode)")
                    }
                },
                mode = CameraMode.PREVIEW_ONLY  // ⚡ Start in PREVIEW_ONLY for optimal performance
            )

            // Set normal mode (not headless) for UI preview
            previewCameraManager.setHeadlessMode(false)

            // Add surface recreation callback for camera switching
            previewCameraManager.setSurfaceRecreationCallback(object : CameraManager.SurfaceRecreationCallback {
                override fun onCameraSwitchCompleted(newPreview: Preview) {
                    surfaceRecreationTrigger++
                    Log.d(TAG, "Preview camera switch completed - surface recreation trigger: $surfaceRecreationTrigger")
                }
            })

            Log.d(TAG, "Preview camera manager initialized in PREVIEW_ONLY mode (zero CPU processing)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize preview camera components", e)
        }
    }

    private fun startStreaming() {
        try {
            // Stop preview camera to avoid resource conflicts
            if (::previewCameraManager.isInitialized) {
                previewCameraManager.stopStreaming()
                Log.d(TAG, "Stopped preview camera to avoid conflicts with streaming service")
            }

            // Start background streaming service (single source)
            StreamingService.startStreamingService(this)

            // Bind to service for communication
            val intent = Intent(this, StreamingService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            // Note: Service will update our state via callback
            Log.d(TAG, "Streaming service started - waiting for callback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming service", e)
        }
    }

    private fun stopStreaming() {
        try {
            // Stop background streaming service (single source)
            StreamingService.stopStreamingService(this)

            // Unbind from service
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }

            // Restart preview camera for UI display
            if (::previewCameraManager.isInitialized) {
                previewCameraManager.startStreaming()
                Log.d(TAG, "Restarted preview camera for UI display")
            }

            // Note: Service will update our state via callback
            Log.d(TAG, "Streaming service stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop streaming service", e)
        }
    }

    private fun transferYouTubeToTV() {
        currentYouTubeUrl?.let { url ->
            val videoId = com.fitnessmirror.app.utils.YouTubeUrlValidator.extractVideoId(url)
            if (videoId != null && hasConnectedClient && isServiceBound) {
                try {
                    // Pass current video time for synchronization
                    streamingService?.transferYouTubeToTV(videoId, currentVideoTime)
                    isYouTubeOnTV = true
                    Log.d(TAG, "YouTube transferred to TV via streaming service: $videoId at ${currentVideoTime}s")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during YouTube transfer", e)
                }
            } else {
                Log.w(TAG, "Cannot transfer YouTube: videoId=$videoId, hasClient=$hasConnectedClient, serviceBound=$isServiceBound")
            }
        }
    }

    private fun returnYouTubeToPhone() {
        if (isYouTubeOnTV && hasConnectedClient && isServiceBound) {
            try {
                streamingService?.returnYouTubeToPhone()
                isYouTubeOnTV = false
                Log.d(TAG, "YouTube returned to phone via streaming service")
            } catch (e: Exception) {
                Log.e(TAG, "Error during YouTube return", e)
            }
        } else {
            Log.w(TAG, "Cannot return YouTube: onTV=$isYouTubeOnTV, hasClient=$hasConnectedClient, serviceBound=$isServiceBound")
        }
    }

    fun switchCamera() {
        if (isStreaming && isServiceBound && streamingService != null) {
            // When streaming, switch via service
            streamingService?.switchCamera()
            Log.d(TAG, "Switched camera via streaming service")
        } else {
            // When not streaming, switch preview camera via NativeCameraView
            // Toggle camera state
            isFrontCamera = !isFrontCamera

            // Increment trigger to notify NativeCameraView to switch camera
            surfaceRecreationTrigger++

            Log.d(TAG, "Switched camera via NativeCameraView - trigger: $surfaceRecreationTrigger, isFrontCamera: $isFrontCamera")
        }
    }

    private fun updateVideoTime(time: Float) {
        currentVideoTime = time
        Log.d(TAG, "Video time updated: ${time}s")
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "FitnessMirror::StreamingWakeLock"
                )
            }

            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(3600000L) // 1 hour max - automatic release for safety
                Log.d(TAG, "Enhanced wake lock acquired (SCREEN_DIM_WAKE_LOCK) for background streaming")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire enhanced wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }

    fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn

        if (keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d(TAG, "Screen keep-on enabled")
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d(TAG, "Screen keep-on disabled")
        }
    }



    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Activity paused - camera managed by StreamingService")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Activity resumed - camera managed by StreamingService")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "Activity stopped")
        // Additional cleanup when activity is not visible
        // Streaming server continues running for TV connection
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "Activity started")
        // Camera management handled in onResume
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: ${newConfig.orientation}")
        // Handle configuration changes gracefully - no need to recreate activity
        // Camera and streaming continue normally
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed")

        if (isStreaming) {
            stopStreaming()
        }

        // Cleanup service connection
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
                isServiceBound = false
                Log.d(TAG, "Service unbound on activity destroy")
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
        }

        // Cleanup preview camera manager
        if (::previewCameraManager.isInitialized) {
            previewCameraManager.cleanup()
            Log.d(TAG, "Preview camera manager cleaned up")
        }

        // Note: Streaming camera cleanup and wake lock handled by service
        Log.d(TAG, "MainActivity cleanup complete - service manages streaming resources")
    }
}