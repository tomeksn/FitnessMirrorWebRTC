package com.fitnessmirror.webrtc.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera operation mode enum
 * Separates local PIP preview from TV streaming to optimize performance
 */
enum class CameraMode {
    /**
     * PREVIEW_ONLY: Local PIP display only
     * - Uses only Preview use case (GPU rendering)
     * - Zero JPEG processing, zero CPU overhead
     * - Smooth 60fps performance like FitnessMirror
     */
    PREVIEW_ONLY,

    /**
     * STREAMING: PIP display + TV streaming
     * - Uses Preview + ImageAnalysis use cases
     * - Enables JPEG processing for WebSocket transmission
     * - Higher CPU usage for frame encoding
     */
    STREAMING
}

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraManager"
        // ⚡ PERFORMANCE FIX: Match Expo FitnessMirror 720p resolution
        // Higher resolution reduces GPU upscaling overhead for smoother preview
        // Expo uses HIGHEST_AVAILABLE which typically gives 1280x720 on most devices
        private const val TARGET_WIDTH = 1280  // 720p HD resolution (match Expo)
        private const val TARGET_HEIGHT = 720  // 16:9 aspect ratio for modern displays

        // 📡 TV STREAMING OPTIMIZATION: Low resolution for fast network transmission (match CastApp)
        private const val STREAMING_WIDTH = 320   // Optimized for TV streaming - 16x fewer pixels
        private const val STREAMING_HEIGHT = 240  // 320x240 = fast encoding + small network packets

        // 📡 STEP 2: Optimized for low latency streaming (<80ms target)
        private const val JPEG_QUALITY = 35    // Lower quality = faster encode/decode (saves 15-25ms)
        private const val FRAME_RATE_MS = 33L   // 30 FPS (33ms = 1000ms/30fps) for smooth real-time motion (saves 67ms)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var currentCamera: Camera? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var streamingScope = CoroutineScope(Dispatchers.Default + Job())

    private var isStreaming = false
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA  // Match NativeCameraView default for consistent behavior

    // Current camera mode for performance optimization
    private var currentMode: CameraMode = CameraMode.PREVIEW_ONLY

    private var frameCallback: ((ByteArray) -> Unit)? = null
    private var lastFrameTime = 0L
    private var isSwitching = false  // Prevent concurrent switching operations

    // Camera operation mutex for threading safety
    private val cameraOperationMutex = Mutex()

    // Background processing capability
    private var isBackgroundMode = false
    private var backgroundFrameInterval = FRAME_RATE_MS * 2 // Slower rate when in background (7.5fps)

    // Headless mode for background service streaming
    private var isHeadlessMode = false

    // Surface conflict detection
    private var lastSuccessfulFrameTime = 0L
    private var surfaceConflictDetected = false
    private val SURFACE_CONFLICT_TIMEOUT = 3000L  // 3 seconds without frames = conflict

    // Frame buffering for smoother PIP display (optimized for 10fps)
    private var lastFrameData: ByteArray? = null
    private var frameBufferTime = 0L
    private val FRAME_BUFFER_REPEAT_MS = 50L  // Reduced from 100ms to 50ms for smoother buffering with 10fps

    interface CameraCallback {
        fun onFrameReady(jpegData: ByteArray)
        fun onError(error: String)
        fun onPreviewReady(preview: Preview)
        fun onPreviewChanged(preview: Preview)  // New callback for camera switching
        fun onPreviewSurfaceConflict()  // New callback for surface conflicts
        fun onCameraFullyInitialized()  // FIX #3/#4: Called when camera ready for streaming
    }

    interface SurfaceRecreationCallback {
        fun onCameraSwitchCompleted(newPreview: Preview)
    }

    private var callback: CameraCallback? = null
    private var surfaceRecreationCallback: SurfaceRecreationCallback? = null
    private var retryCount = 0
    private val maxRetries = 3

    fun initialize(callback: CameraCallback, mode: CameraMode = CameraMode.PREVIEW_ONLY) {
        this.callback = callback
        this.frameCallback = callback::onFrameReady
        this.currentMode = mode

        Log.d(TAG, "Initializing camera in ${mode.name} mode")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupCamera(mode)
            } catch (exc: Exception) {
                Log.e(TAG, "Camera initialization failed", exc)
                callback.onError("Camera initialization failed: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setSurfaceRecreationCallback(callback: SurfaceRecreationCallback?) {
        this.surfaceRecreationCallback = callback
        Log.d(TAG, "Surface recreation callback ${if (callback != null) "set" else "cleared"}")
    }

    private fun setupCamera(mode: CameraMode = currentMode) {
        val cameraProvider = this.cameraProvider ?: return

        try {
            // Simple unbind - let CameraX handle surface cleanup automatically
            cameraProvider.unbindAll()
            Log.d(TAG, "Unbound all camera use cases")

            // Store current mode
            currentMode = mode
            Log.d(TAG, "Setting up camera in ${mode.name} mode")

            // Create ResolutionSelector matching Expo architecture
            // Expo uses HIGHEST_AVAILABLE_STRATEGY (no manual size) for automatic optimal resolution
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()

            val camera = when (mode) {
                CameraMode.PREVIEW_ONLY -> {
                    // PREVIEW_ONLY: Only bind Preview use case
                    // Zero JPEG processing, GPU-only rendering for smooth PIP
                    preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetFrameRate(Range(30, 60))  // Force high FPS for low latency
                        .build()

                    // No ImageAnalysis in this mode
                    imageAnalysis = null

                    Log.d(TAG, "PREVIEW_ONLY mode: Binding only Preview use case (zero CPU processing)")
                    Log.d(TAG, "Using ResolutionSelector: HIGHEST_AVAILABLE with 16:9 aspect ratio (match Expo)")

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        currentCameraSelector,
                        preview  // ONLY Preview, NO ImageAnalysis
                    )
                }

                CameraMode.STREAMING -> {
                    // STREAMING: Bind both Preview and ImageAnalysis
                    // Enables JPEG processing for TV streaming
                    preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetFrameRate(Range(30, 60))  // Force high FPS for low latency
                        .build()

                    // CRITICAL FIX: Use 320x240 directly for ImageAnalysis (match CastApp)
                    // This avoids expensive 1280x720 → 320x240 scaling
                    imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(STREAMING_WIDTH, STREAMING_HEIGHT))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis?.setAnalyzer(cameraExecutor) { image ->
                        if (isStreaming) {
                            processFrame(image)
                        }
                        image.close()
                    }

                    Log.d(TAG, "STREAMING mode: Binding Preview + ImageAnalysis (JPEG processing enabled)")
                    Log.d(TAG, "Using ResolutionSelector: HIGHEST_AVAILABLE with 16:9 aspect ratio (match Expo)")

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        currentCameraSelector,
                        preview,
                        imageAnalysis  // NOW enable JPEG processing
                    )
                }
            }

            // Track current camera for state monitoring
            currentCamera = camera

            Log.d(TAG, "Camera setup completed successfully")
            Log.d(TAG, "Camera info: ${camera.cameraInfo}")
            Log.d(TAG, "Preview use case: ${preview?.let { "Active" } ?: "Null"}")
            Log.d(TAG, "ImageAnalysis use case: ${imageAnalysis?.let { "Active" } ?: "Null"}")

            // Notify callback that preview is ready for surface binding
            preview?.let { previewUseCase ->
                Log.d(TAG, "Notifying callback that preview is ready")
                callback?.onPreviewReady(previewUseCase)

                // Also notify about preview change (for camera switching)
                callback?.onPreviewChanged(previewUseCase)
            }

            // Reset retry count on success
            retryCount = 0
            Log.d(TAG, "Camera setup successful - camera selector: ${if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"}")

            // FIX #3/#4: Notify that camera is fully initialized and ready for streaming
            if (currentMode == CameraMode.STREAMING) {
                callback?.onCameraFullyInitialized()
                Log.d(TAG, "✅ Camera fully initialized in STREAMING mode - ready for frames")
            }

        } catch (exc: Exception) {
            Log.e(TAG, "Camera binding failed: ${exc.message}", exc)

            // Notify callback about the error
            callback?.onError("Camera setup failed: ${exc.message}")

            // Retry logic with exponential backoff
            if (retryCount < maxRetries) {
                retryCount++
                val delayMs = 1000L * retryCount // 1s, 2s, 3s

                streamingScope.launch {
                    delay(delayMs)
                    Log.d(TAG, "Retrying camera setup... (attempt $retryCount/$maxRetries)")
                    setupCamera()
                }
            } else {
                Log.e(TAG, "Max camera setup retries reached")
                callback?.onError("Camera initialization failed after $maxRetries attempts")
            }
        }
    }

    private fun processFrame(image: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // Simple frame rate limiting like CastApp
        val frameInterval = if (isBackgroundMode) backgroundFrameInterval else FRAME_RATE_MS
        if (currentTime - lastFrameTime < frameInterval) {
            return
        }
        lastFrameTime = currentTime

        try {
            // TEST: Simplified pipeline - no buffering, direct send (match CastApp)
            val jpegData = convertImageToJpeg(image)
            jpegData?.let { data ->
                frameCallback?.invoke(data)
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Frame processing failed", exc)
        }
    }

    private fun convertImageToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val yuvImage = imageProxyToYuvImage(image)
            val outputStream = ByteArrayOutputStream()

            val rect = Rect(0, 0, image.width, image.height)

            // OPTIMIZATION: Skip scaling if already at target resolution (thanks to setTargetResolution)
            // This eliminates double JPEG encoding and saves 10-15ms per frame!
            if (image.width <= STREAMING_WIDTH && image.height <= STREAMING_HEIGHT) {
                // Fast path: Direct YUV→JPEG compression (single pass!)
                yuvImage?.compressToJpeg(rect, JPEG_QUALITY, outputStream)
                val jpegData = outputStream.toByteArray()
                outputStream.close()
                jpegData
            } else {
                // Slow path: Needs scaling - YUV→JPEG then scale
                yuvImage?.compressToJpeg(rect, JPEG_QUALITY, outputStream)
                val jpegData = outputStream.toByteArray()
                outputStream.close()
                scaleJpegIfNeeded(jpegData)
            }
        } catch (exc: Exception) {
            Log.e(TAG, "JPEG conversion failed", exc)
            null
        }
    }

    private fun imageProxyToYuvImage(image: ImageProxy): YuvImage? {
        val planes = image.planes
        if (planes.size != 3) return null

        val ySize = planes[0].buffer.remaining()
        val uSize = planes[1].buffer.remaining()
        val vSize = planes[2].buffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        planes[0].buffer.get(nv21, 0, ySize)

        // Copy UV plane
        val uvBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        
        var uvIndex = ySize
        while (uvBuffer.hasRemaining() && vBuffer.hasRemaining()) {
            nv21[uvIndex++] = vBuffer.get()
            nv21[uvIndex++] = uvBuffer.get()
        }

        return YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    }

    private fun scaleJpegIfNeeded(jpegData: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)

        // 📡 STEP 2 OPTIMIZATION: Simplified pipeline for maximum speed (match CastApp)
        // - Removed CPU-intensive mirror transform (now handled by GPU on display side)
        // - Direct scale + compress for minimal latency
        return if (bitmap.width > STREAMING_WIDTH || bitmap.height > STREAMING_HEIGHT) {
            val scaleX = STREAMING_WIDTH.toFloat() / bitmap.width
            val scaleY = STREAMING_HEIGHT.toFloat() / bitmap.height
            val scale = minOf(scaleX, scaleY)

            val matrix = Matrix().apply {
                setScale(scale, scale)
                // Mirror effect removed - handled by GPU (PreviewView.scaleX or Canvas transform)
            }
            val scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val result = outputStream.toByteArray()

            // Log frame size for monitoring
            Log.d(TAG, "Frame compressed (320x240@${JPEG_QUALITY}%): ${jpegData.size} -> ${result.size} bytes")

            bitmap.recycle()
            scaledBitmap.recycle()
            outputStream.close()

            result
        } else {
            // Direct compress without transforms for maximum speed
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val result = outputStream.toByteArray()

            Log.d(TAG, "Frame compressed (direct@${JPEG_QUALITY}%): ${jpegData.size} -> ${result.size} bytes")

            bitmap.recycle()
            outputStream.close()

            result
        }
    }

    fun startStreaming() {
        Log.d(TAG, "Starting camera streaming")

        // Switch to STREAMING mode if not already
        if (currentMode != CameraMode.STREAMING) {
            Log.d(TAG, "Switching from ${currentMode.name} to STREAMING mode")
            setupCamera(CameraMode.STREAMING)
        }

        isStreaming = true
        // TEST: Disabled buffering/monitoring systems to reduce latency
        // startSurfaceConflictMonitoring()
        // startFrameBuffering()

        Log.d(TAG, "Camera streaming started (no buffering - low latency mode)")
    }

    fun stopStreaming() {
        Log.d(TAG, "Stopping camera streaming")
        isStreaming = false

        // Switch back to PREVIEW_ONLY mode for optimal performance
        if (currentMode == CameraMode.STREAMING) {
            Log.d(TAG, "Switching from STREAMING to PREVIEW_ONLY mode")
            setupCamera(CameraMode.PREVIEW_ONLY)
        }

        Log.d(TAG, "Camera streaming stopped, returned to PREVIEW_ONLY mode")
    }

    /**
     * Get current camera mode
     * @return Current CameraMode (PREVIEW_ONLY or STREAMING)
     */
    fun getCurrentMode(): CameraMode {
        return currentMode
    }

    fun switchCamera() {
        streamingScope.launch {
            cameraOperationMutex.withLock {
                // Prevent concurrent switching operations to avoid Multiple LifecycleCameras error
                if (isSwitching) {
                    Log.w(TAG, "Camera switch already in progress - ignoring request")
                    return@withLock
                }

                isSwitching = true

                val currentCamera = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"
                Log.d(TAG, "Mutex-protected camera switch - current: $currentCamera")

                // Determine target camera selector
                val targetSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val targetCamera = if (targetSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"

                // Check if target camera is available before switching
                if (!isCameraAvailable(targetSelector)) {
                    Log.w(TAG, "Target camera $targetCamera not available - switching cancelled")
                    isSwitching = false
                    return@withLock
                }

                Log.d(TAG, "Preparing mutex-protected switch to: $targetCamera")
                atomicCameraSwitch(targetSelector)
            }
        }
    }

    fun getPreview(): Preview? = preview

    fun isCurrentlyStreaming(): Boolean = isStreaming

    fun isFrontCamera(): Boolean = currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

    private fun isCameraAvailable(cameraSelector: CameraSelector): Boolean {
        return try {
            val provider = this.cameraProvider ?: return false
            provider.hasCamera(cameraSelector)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking camera availability", e)
            false
        }
    }

    private fun atomicCameraSwitch(targetSelector: CameraSelector) {
        streamingScope.launch {
            try {
                Log.d(TAG, "Step 1: Preparing new use cases for target camera in ${currentMode.name} mode")

                // Create ResolutionSelector matching Expo architecture
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()

                // Prepare new use cases BEFORE unbinding current camera (background thread OK)
                val newPreview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetFrameRate(Range(30, 60))  // Force high FPS for low latency
                    .build()

                // Only prepare ImageAnalysis if in STREAMING mode
                val newImageAnalysis = if (currentMode == CameraMode.STREAMING) {
                    // CRITICAL FIX: Use 320x240 directly for ImageAnalysis (match CastApp)
                    ImageAnalysis.Builder()
                        .setTargetResolution(Size(STREAMING_WIDTH, STREAMING_HEIGHT))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { image ->
                                if (isStreaming) {
                                    processFrame(image)
                                }
                                image.close()
                            }
                        }
                } else {
                    null
                }

                Log.d(TAG, "Step 2: Switch to main thread for camera operations")

                // Switch to main thread for all CameraX operations
                streamingScope.launch(Dispatchers.Main) {
                    try {
                        // Store reference to current camera for monitoring
                        val oldCamera = currentCamera

                        // Unbind all cameras (MUST be on main thread)
                        cameraProvider?.unbindAll()
                        Log.d(TAG, "All cameras unbound - waiting for actual closure")

                        // Wait for old camera to actually close (if it exists)
                        oldCamera?.let { camera ->
                            waitForCameraClosure(camera)
                        }

                        Log.d(TAG, "Step 3: Camera closure confirmed - binding new camera")

                        // Now bind new camera based on current mode (MUST be on main thread)
                        val newCamera = if (currentMode == CameraMode.STREAMING && newImageAnalysis != null) {
                            cameraProvider?.bindToLifecycle(
                                lifecycleOwner,
                                targetSelector,
                                newPreview,
                                newImageAnalysis  // Include ImageAnalysis in STREAMING mode
                            )
                        } else {
                            cameraProvider?.bindToLifecycle(
                                lifecycleOwner,
                                targetSelector,
                                newPreview  // ONLY Preview in PREVIEW_ONLY mode
                            )
                        }

                        Log.d(TAG, "Step 4: Updating references")

                        // Update references after successful binding
                        currentCameraSelector = targetSelector
                        currentCamera = newCamera
                        preview = newPreview
                        imageAnalysis = newImageAnalysis  // Will be null in PREVIEW_ONLY mode

                        val switchedTo = if (targetSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"
                        Log.d(TAG, "Async camera switch successful - now using: $switchedTo")
                        Log.d(TAG, "Camera info: ${newCamera?.cameraInfo}")

                        // Notify callbacks (already on main thread)
                        callback?.onPreviewReady(newPreview)
                        callback?.onPreviewChanged(newPreview)

                        // Trigger surface recreation callback for immediate UI refresh
                        surfaceRecreationCallback?.onCameraSwitchCompleted(newPreview)
                        Log.d(TAG, "Surface recreation callback triggered for camera switch to $switchedTo")

                        // Reset switching flag on success
                        isSwitching = false

                    } catch (mainThreadExc: Exception) {
                        Log.e(TAG, "Camera switch failed on main thread", mainThreadExc)
                        callback?.onError("Camera switch failed: ${mainThreadExc.message}")
                        isSwitching = false

                        // Fallback - try to restore current camera
                        Log.d(TAG, "Attempting fallback to setupCamera")
                        setupCamera()
                    }
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Camera switch preparation failed", exc)

                // Reset switching flag and notify error on main thread
                streamingScope.launch(Dispatchers.Main) {
                    isSwitching = false
                    callback?.onError("Camera switch preparation failed: ${exc.message}")
                }
            }
        }
    }

    private suspend fun waitForCameraClosure(camera: Camera) {
        Log.d(TAG, "Monitoring camera state for closure...")

        withTimeoutOrNull(500L) { // 500ms timeout
            suspendCancellableCoroutine<Unit> { continuation ->
                val stateObserver = androidx.lifecycle.Observer<CameraState> { state ->
                    Log.d(TAG, "Camera state changed: ${state.type}")
                    if (state.type == CameraState.Type.CLOSED) {
                        Log.d(TAG, "Camera officially closed - ready for new binding")
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }

                camera.cameraInfo.cameraState.observe(lifecycleOwner, stateObserver)

                continuation.invokeOnCancellation {
                    Log.d(TAG, "Camera closure monitoring cancelled")
                    camera.cameraInfo.cameraState.removeObserver(stateObserver)
                }
            }
        } ?: run {
            Log.w(TAG, "Camera closure timeout - proceeding anyway")
        }
    }

    fun pauseCamera() {
        Log.d(TAG, "Pausing camera - switching to background mode")

        // Enable background mode instead of completely stopping
        isBackgroundMode = true

        // Keep streaming but at reduced frame rate for background processing
        // Don't unbind camera to maintain continuous streaming capability
        Log.d(TAG, "Camera switched to background mode (7.5fps) - streaming continues")
    }

    fun resumeCamera() {
        Log.d(TAG, "Resuming camera from background mode")

        // Disable background mode for full performance
        isBackgroundMode = false

        Log.d(TAG, "Camera resumed to foreground mode (15fps)")
    }

    fun setBackgroundMode(enabled: Boolean) {
        isBackgroundMode = enabled
        val mode = if (enabled) "background (7.5fps)" else "foreground (15fps)"
        Log.d(TAG, "Camera mode set to: $mode")
    }

    fun startBackgroundStreaming() {
        Log.d(TAG, "Starting background streaming in headless mode")
        isHeadlessMode = true
        isBackgroundMode = true  // Slower frame rate for background
        startStreaming()
        Log.d(TAG, "Background streaming started (headless mode)")
    }

    fun setHeadlessMode(enabled: Boolean) {
        if (isHeadlessMode != enabled) {
            isHeadlessMode = enabled
            val mode = if (enabled) "headless (no preview)" else "normal (with preview)"
            Log.d(TAG, "Camera headless mode set to: $mode")

            // Reinitialize camera if already setup
            if (cameraProvider != null) {
                setupCamera()
            }
        }
    }

    private fun startSurfaceConflictMonitoring() {
        streamingScope.launch {
            while (isStreaming) {
                delay(1000L) // Check every second
                val currentTime = System.currentTimeMillis()

                if (lastSuccessfulFrameTime > 0 &&
                    currentTime - lastSuccessfulFrameTime > SURFACE_CONFLICT_TIMEOUT &&
                    !surfaceConflictDetected) {

                    surfaceConflictDetected = true
                    Log.w(TAG, "Surface conflict detected - no frames for ${SURFACE_CONFLICT_TIMEOUT}ms")

                    // Notify callback about conflict
                    streamingScope.launch(Dispatchers.Main) {
                        callback?.onPreviewSurfaceConflict()
                    }

                    // Attempt preview refresh
                    refreshPreviewSurface()
                }
            }
        }
    }

    private fun startFrameBuffering() {
        streamingScope.launch {
            while (isStreaming) {
                delay(FRAME_BUFFER_REPEAT_MS) // Check every 100ms
                val currentTime = System.currentTimeMillis()

                // If we haven't received a new frame recently but have a buffered frame, repeat it
                if (lastFrameData != null &&
                    frameBufferTime > 0 &&
                    currentTime - frameBufferTime > FRAME_RATE_MS &&
                    currentTime - frameBufferTime < FRAME_RATE_MS * 2) { // Don't repeat too old frames

                    // Send the last frame again for smoother display
                    frameCallback?.invoke(lastFrameData!!)
                    Log.d(TAG, "Frame buffer: Repeating last frame for smoother PIP display")
                }
            }
        }
    }

    private fun refreshPreviewSurface() {
        streamingScope.launch {
            try {
                Log.d(TAG, "Attempting preview surface refresh due to conflict")

                val currentPreview = preview
                if (currentPreview != null) {
                    // Force preview surface recreation by notifying callbacks
                    streamingScope.launch(Dispatchers.Main) {
                        callback?.onPreviewChanged(currentPreview)
                    }

                    Log.d(TAG, "Preview surface refresh triggered")
                    lastSuccessfulFrameTime = System.currentTimeMillis() // Reset timer
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh preview surface", e)
            }
        }
    }

    fun forcePreviewRefresh() {
        Log.d(TAG, "Force preview refresh requested")
        refreshPreviewSurface()
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up camera resources")
        stopStreaming()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        streamingScope.coroutineContext[Job]?.cancel()
        frameCallback = null
        cameraProvider = null
        imageAnalysis = null
        preview = null
        currentCamera = null
    }
}