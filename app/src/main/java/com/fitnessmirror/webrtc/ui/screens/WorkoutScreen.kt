package com.fitnessmirror.webrtc.ui.screens

import android.view.View
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import com.fitnessmirror.webrtc.R
import com.fitnessmirror.webrtc.ui.components.DraggableCameraPIP
import com.fitnessmirror.webrtc.ui.components.ConnectionStatusPanel
import com.fitnessmirror.webrtc.camera.CameraManager
import com.fitnessmirror.webrtc.ui.theme.FitnessMirrorNativeTheme
import com.fitnessmirror.webrtc.utils.YouTubeUrlValidator

@Composable
fun WorkoutScreen(
    youtubeUrl: String,
    isStreaming: Boolean,
    serverAddress: String?,
    hasConnectedClient: Boolean,
    isYouTubeOnTV: Boolean,
    videoStartTime: Float = 0f,
    cameraManager: CameraManager?,
    surfaceRecreationTrigger: Int = 0,
    isFrontCamera: Boolean = false,
    hasCameraPermission: Boolean = true,
    keepScreenOn: Boolean = false,
    onBack: () -> Unit,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onReturnYouTubeToPhone: () -> Unit,
    onSwitchCamera: () -> Unit,
    onVideoTimeUpdate: (Float) -> Unit = {},
    onToggleKeepScreenOn: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header with Back Button (only in portrait)
            if (!isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.back_button),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isYouTubeOnTV) {
                    // Show "Playing on TV" message when video is on TV
                    PlayingOnTVMessage(
                        serverAddress = serverAddress,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Show YouTube Player on phone
                    YouTubePlayer(
                        youtubeUrl = youtubeUrl,
                        startTime = videoStartTime,
                        onTimeUpdate = onVideoTimeUpdate,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Controls - adaptive layout for orientation
            if (!isLandscape) {
                // Portrait: Controls at bottom
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Connection Status Panel
                    ConnectionStatusPanel(
                        isStreaming = isStreaming,
                        serverAddress = serverAddress,
                        hasConnectedClient = hasConnectedClient,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Control Buttons
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Main streaming control
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(
                                onClick = if (isStreaming) onStopStreaming else onStartStreaming,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isStreaming)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    text = if (isStreaming)
                                        stringResource(R.string.stop_streaming)
                                    else
                                        stringResource(R.string.start_streaming)
                                )
                            }

                            // Camera switch button
                            if (isStreaming) {
                                Button(
                                    onClick = onSwitchCamera,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Text(stringResource(R.string.switch_camera))
                                }
                            }
                        }

                        // YouTube casting control
                        if (isYouTubeOnTV && hasConnectedClient) {
                            Button(
                                onClick = onReturnYouTubeToPhone,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                ),
                                modifier = Modifier.fillMaxWidth(0.8f)
                            ) {
                                Text(stringResource(R.string.return_to_phone))
                            }
                        }

                        // Keep Screen On toggle
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            androidx.compose.material3.Switch(
                                checked = keepScreenOn,
                                onCheckedChange = { onToggleKeepScreenOn() }
                            )
                            Text(
                                text = "Keep Screen On",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.info_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        // Landscape controls - outside Column, inside main Box
        if (isLandscape) {
            // Landscape: Back button in top-left corner
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.back_button),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Landscape: Floating controls panel on the left
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(16.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Connection Status Panel (compact)
                ConnectionStatusPanel(
                    isStreaming = isStreaming,
                    serverAddress = serverAddress,
                    hasConnectedClient = hasConnectedClient,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Compact Control Buttons
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Main streaming control
                    Button(
                        onClick = if (isStreaming) onStopStreaming else onStartStreaming,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isStreaming)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.width(140.dp)
                    ) {
                        Text(
                            text = if (isStreaming)
                                stringResource(R.string.stop_streaming)
                            else
                                stringResource(R.string.start_streaming),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Camera switch button
                    if (isStreaming) {
                        Button(
                            onClick = onSwitchCamera,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier.width(140.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.switch_camera),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // YouTube casting control
                    if (isYouTubeOnTV && hasConnectedClient) {
                        Button(
                            onClick = onReturnYouTubeToPhone,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ),
                            modifier = Modifier.width(140.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.return_to_phone),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // Keep Screen On toggle (compact)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        androidx.compose.material3.Switch(
                            checked = keepScreenOn,
                            onCheckedChange = { onToggleKeepScreenOn() },
                            modifier = Modifier.scale(0.8f)
                        )
                        Text(
                            text = "Keep On",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }

        // Draggable Camera PIP Overlay (always on top)
        DraggableCameraPIP(
            cameraManager = cameraManager,
            modifier = Modifier.fillMaxSize(),
            initialX = if (isLandscape) {
                // In landscape, position near the right edge
                configuration.screenWidthDp.toFloat() - 140f
            } else {
                20f
            },
            initialY = if (isLandscape) {
                20f // Top of screen in landscape
            } else {
                100f // Below header in portrait
            },
            initialWidth = if (isLandscape) 120f else 120f,
            initialHeight = if (isLandscape) 90f else 160f, // Shorter in landscape
            onDoubleTap = onSwitchCamera,
            surfaceRecreationTrigger = surfaceRecreationTrigger,
            isYouTubeOnTV = isYouTubeOnTV,  // Pass YouTube state for surface recreation strategy
            isFrontCamera = isFrontCamera,  // Pass camera facing for GPU mirror transform
            hasCameraPermission = hasCameraPermission  // Pass camera permission state
        )
    }
}

@Composable
private fun PlayingOnTVMessage(
    serverAddress: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1a1a1a),
                        Color(0xFF2d2d2d)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // TV Icon
            Text(
                text = "📺",
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Main message
            Text(
                text = stringResource(R.string.playing_on_tv_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // Subtitle
            Text(
                text = stringResource(R.string.playing_on_tv_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            // Control info
            Card(
                modifier = Modifier.padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "💡 Controls Available",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "• Use 'Return to Phone' button below to bring video back",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Text(
                        text = "• Camera stream continues on TV",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Text(
                        text = "• Use 'Switch Camera' to change front/back camera",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // Server address info
            serverAddress?.let { address ->
                Card(
                    modifier = Modifier.padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.tv_connection_address),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubePlayer(
    youtubeUrl: String,
    startTime: Float = 0f,
    onTimeUpdate: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoId = remember(youtubeUrl) {
        YouTubeUrlValidator.extractVideoId(youtubeUrl)
    }

    if (videoId == null) {
        // Fallback for invalid URL
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Invalid YouTube URL",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Red
                )
                Text(
                    text = "Please enter a valid YouTube link",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
        return
    }

    // Use stable AndroidYouTubePlayerView with IFramePlayerOptions
    AndroidView(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        factory = { context ->
            YouTubePlayerView(context).apply {
                // Enable hardware acceleration to match Expo FitnessMirror architecture
                // YouTube WebView (GPU) + Camera SurfaceView (hardware overlay) = no conflict
                // Both use separate rendering pipelines for optimal performance
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                android.util.Log.d("YouTubePlayer", "Hardware acceleration enabled for YouTube WebView")

                // CRITICAL: Disable automatic initialization BEFORE calling initialize()
                enableAutomaticInitialization = false

                // Add to lifecycle to handle proper cleanup
                if (context is androidx.lifecycle.LifecycleOwner) {
                    context.lifecycle.addObserver(this)
                }

                // Configure IFrame options to fix error 152-15
                val iFramePlayerOptions = IFramePlayerOptions.Builder(context)
                    .controls(1)  // Show controls
                    .rel(0)  // Don't show related videos
                    .ivLoadPolicy(3)  // Load annotations
                    .ccLoadPolicy(1)  // Show captions by default
                    .build()

                // Initialize with options and listener
                initialize(object : AbstractYouTubePlayerListener() {
                    override fun onReady(youTubePlayer: YouTubePlayer) {
                        // Load video when player is ready, with start time if specified
                        youTubePlayer.loadVideo(videoId, startTime)
                        android.util.Log.d("YouTubePlayer", "Player ready, loading video: $videoId at ${startTime}s")
                    }

                    override fun onError(youTubePlayer: YouTubePlayer, error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError) {
                        android.util.Log.e("YouTubePlayer", "Player error: $error")
                    }

                    override fun onStateChange(youTubePlayer: YouTubePlayer, state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState) {
                        android.util.Log.d("YouTubePlayer", "Player state changed: $state")
                    }

                    override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                        // Report current time for synchronization
                        onTimeUpdate(second)
                    }
                }, iFramePlayerOptions)
            }
        }
    )

    // Proper lifecycle management
    DisposableEffect(videoId) {
        onDispose {
            android.util.Log.d("YouTubePlayer", "Disposing YouTube player for video: $videoId")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WorkoutScreenPreview() {
    FitnessMirrorNativeTheme {
        WorkoutScreen(
            youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            isStreaming = false,
            serverAddress = null,
            hasConnectedClient = false,
            isYouTubeOnTV = false,
            videoStartTime = 0f,
            cameraManager = null,
            surfaceRecreationTrigger = 0,
            keepScreenOn = false,
            onBack = { },
            onStartStreaming = { },
            onStopStreaming = { },
            onReturnYouTubeToPhone = { },
            onSwitchCamera = { },
            onVideoTimeUpdate = { },
            onToggleKeepScreenOn = { }
        )
    }
}