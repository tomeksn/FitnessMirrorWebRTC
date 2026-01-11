package com.fitnessmirror.webrtc.camera

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Extension function to await ListenableFuture (simplified version without kotlinx-coroutines-guava)
suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        Runnable::run  // Execute on current thread
    )
}

/**
 * Native Camera View - Direct port of Expo Camera architecture
 *
 * Key differences from Compose AndroidView approach:
 * - PreviewView is direct child of FrameLayout (native view hierarchy)
 * - No Compose re-composition overhead
 * - Uses UseCaseGroup for binding (like Expo)
 * - Matches Expo's low-latency preview architecture
 */
class NativeCameraView(
    context: Context,
    private val lifecycleOwner: LifecycleOwner
) : FrameLayout(context) {

    companion object {
        private const val TAG = "NativeCameraView"
    }

    private val previewView = PreviewView(context).apply {
        elevation = 0f
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // Default PERFORMANCE mode (SurfaceView) - stable, no BufferQueue errors
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    var lensFacing = CameraSelector.LENS_FACING_FRONT
        set(value) {
            field = value
            recreateCamera()
        }

    init {
        // Add PreviewView as child (native view hierarchy like Expo)
        addView(
            previewView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        Log.d(TAG, "NativeCameraView initialized with native view hierarchy")

        // Initialize camera
        scope.launch {
            setupCamera()
        }
    }

    private suspend fun setupCamera() {
        try {
            Log.d(TAG, "Setting up camera...")

            val provider = ProcessCameraProvider.getInstance(context).await()
            cameraProvider = provider

            createCamera()

            Log.d(TAG, "Camera setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup camera", e)
        }
    }

    private fun createCamera() {
        val provider = cameraProvider ?: return

        try {
            // Build resolution selector matching Expo
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()

            // Create Preview use case
            preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also {
                    // Connect to PreviewView surface provider
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Create camera selector
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            // Build UseCaseGroup (like Expo)
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview!!)
                .build()

            // Unbind all before rebinding
            provider.unbindAll()

            // Bind to lifecycle with UseCaseGroup
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup
            )

            Log.d(TAG, "Camera created successfully with UseCaseGroup, lensFacing=$lensFacing")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera", e)
        }
    }

    private fun recreateCamera() {
        scope.launch {
            Log.d(TAG, "Recreating camera for lens facing change: $lensFacing")
            createCamera()
        }
    }

    fun cleanup() {
        scope.launch {
            try {
                cameraProvider?.unbindAll()
                camera = null
                preview = null
                Log.d(TAG, "Camera cleanup complete")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup camera", e)
            }
        }
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        Log.d(TAG, "Switching camera to lensFacing=$lensFacing")
    }
}
