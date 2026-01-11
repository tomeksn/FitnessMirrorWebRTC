package com.fitnessmirror.webrtc.ui.components

import android.content.Context
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fitnessmirror.webrtc.camera.NativeCameraView
import kotlin.math.roundToInt

@Composable
fun DraggableCameraPIP(
    cameraManager: com.fitnessmirror.app.camera.CameraManager?,  // Deprecated: Not used, kept for API compatibility
    modifier: Modifier = Modifier,
    initialWidth: Float = 120f,
    initialHeight: Float = 160f,
    initialX: Float = 20f,
    initialY: Float = 100f,
    onDoubleTap: () -> Unit = {},
    surfaceRecreationTrigger: Int = 0,  // Parameter for forcing recreation
    isYouTubeOnTV: Boolean = false,  // YouTube state for surface recreation strategy
    isFrontCamera: Boolean = false,  // Camera facing for GPU mirror transform
    hasCameraPermission: Boolean = true  // New: Controls whether to show camera or placeholder
) {
    val density = LocalDensity.current

    // State for position, size and scale
    var offsetX by remember { mutableStateOf(initialX) }
    var offsetY by remember { mutableStateOf(initialY) }
    var scale by remember { mutableStateOf(1f) }

    // Screen bounds for boundary checking
    var screenWidth by remember { mutableStateOf(0f) }
    var screenHeight by remember { mutableStateOf(0f) }

    // Track if this is a configuration change to adjust position
    var lastScreenWidth by remember { mutableStateOf(0f) }
    var lastScreenHeight by remember { mutableStateOf(0f) }

    // Current size calculations
    val currentWidth = initialWidth * scale
    val currentHeight = initialHeight * scale

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                with(density) {
                    val newScreenWidth = size.width.toDp().value
                    val newScreenHeight = size.height.toDp().value

                    // Check if this is a significant screen size change (orientation change)
                    if (lastScreenWidth > 0f && lastScreenHeight > 0f) {
                        val isOrientationChange = kotlin.math.abs(newScreenWidth - lastScreenWidth) > 100f ||
                                                kotlin.math.abs(newScreenHeight - lastScreenHeight) > 100f

                        if (isOrientationChange) {
                            android.util.Log.d("DraggableCameraPIP", "Orientation change detected, adjusting PIP position")

                            // Reset to initial position for new orientation, but keep it within bounds
                            offsetX = initialX.coerceIn(0f, (newScreenWidth - currentWidth).coerceAtLeast(0f))
                            offsetY = initialY.coerceIn(0f, (newScreenHeight - currentHeight).coerceAtLeast(0f))
                        }
                    }

                    lastScreenWidth = screenWidth
                    lastScreenHeight = screenHeight
                    screenWidth = newScreenWidth
                    screenHeight = newScreenHeight
                }
            }
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        with(density) { offsetX.dp.roundToPx() },
                        with(density) { offsetY.dp.roundToPx() }
                    )
                }
                .size(
                    width = with(density) { currentWidth.dp },
                    height = with(density) { currentHeight.dp }
                )
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .border(
                    width = 2.dp,
                    color = Color.White,
                    shape = RoundedCornerShape(10.dp)
                )
                .pointerInput("double_tap") {
                    detectTapGestures(
                        onDoubleTap = {
                            android.util.Log.d("DraggableCameraPIP", "Double tap detected - switching camera")
                            onDoubleTap()
                        }
                    )
                }
                .pointerInput("transform") {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Handle pan (drag)
                        val newOffsetX = (offsetX + pan.x / density.density).coerceIn(
                            0f,
                            (screenWidth - currentWidth).coerceAtLeast(0f)
                        )
                        val newOffsetY = (offsetY + pan.y / density.density).coerceIn(
                            0f,
                            (screenHeight - currentHeight).coerceAtLeast(0f)
                        )

                        offsetX = newOffsetX
                        offsetY = newOffsetY

                        // Handle zoom (pinch)
                        val newScale = (scale * zoom).coerceIn(0.5f, 3f)
                        scale = newScale

                        // Adjust position to keep within bounds after scaling
                        val newCurrentWidth = initialWidth * newScale
                        val newCurrentHeight = initialHeight * newScale

                        if (offsetX > screenWidth - newCurrentWidth) {
                            offsetX = (screenWidth - newCurrentWidth).coerceAtLeast(0f)
                        }
                        if (offsetY > screenHeight - newCurrentHeight) {
                            offsetY = (screenHeight - newCurrentHeight).coerceAtLeast(0f)
                        }
                    }
                }
        ) {
            if (hasCameraPermission) {
                // FIX #2: Stable key prevents AndroidView recreation during recomposition
                key("native_camera_view_stable") {
                    CameraPreview(
                        surfaceRecreationTrigger = surfaceRecreationTrigger,
                        isYouTubePlayingOnPhone = !isYouTubeOnTV,
                        isFrontCamera = isFrontCamera,
                        modifier = Modifier
                            .fillMaxSize()
                            // STEP 2: GPU-based mirror effect for front camera (zero CPU overhead)
                            .graphicsLayer {
                                if (isFrontCamera) {
                                    scaleX = -1f  // Horizontal flip via GPU transform
                                }
                            }
                    )
                }
            } else {
                // Placeholder when camera is not ready
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📷",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Camera",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    surfaceRecreationTrigger: Int = 0,
    isYouTubePlayingOnPhone: Boolean = false,  // Kept for API compatibility but not used
    isFrontCamera: Boolean = false,  // Camera facing for GPU mirror transform
    modifier: Modifier = Modifier
) {
    // Get lifecycle owner from Compose
    val lifecycleOwner = LocalLifecycleOwner.current

    // Store NativeCameraView reference for camera switch
    var nativeCameraView by remember { mutableStateOf<NativeCameraView?>(null) }

    // Handle camera switch when surfaceRecreationTrigger changes
    LaunchedEffect(surfaceRecreationTrigger) {
        if (surfaceRecreationTrigger > 0) {
            nativeCameraView?.switchCamera()
            android.util.Log.d("CameraPreview", "Camera switch triggered via NativeCameraView: $surfaceRecreationTrigger")
        }
    }

    // Native Camera View - Direct port of Expo architecture
    // This bypasses Compose AndroidView overhead and gives us native view hierarchy
    // Uses UseCaseGroup and HIGHEST_AVAILABLE_STRATEGY like Expo
    AndroidView(
        modifier = modifier,
        factory = { context ->
            android.util.Log.d("CameraPreview", "Creating NativeCameraView")
            NativeCameraView(context, lifecycleOwner).also {
                nativeCameraView = it
            }
        },
        update = { view ->
            // No updates needed - mirror is handled by graphicsLayer
        },
        onRelease = { view ->
            android.util.Log.d("CameraPreview", "Releasing NativeCameraView")
            view.cleanup()
            nativeCameraView = null
        }
    )
}