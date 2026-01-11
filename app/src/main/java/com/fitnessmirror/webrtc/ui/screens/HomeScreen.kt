package com.fitnessmirror.webrtc.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fitnessmirror.webrtc.R
import com.fitnessmirror.webrtc.ui.theme.FitnessMirrorNativeTheme
import com.fitnessmirror.webrtc.utils.YouTubeUrlValidator
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onStartWorkout: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var youtubeUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isValidating by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        // Subtitle
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 40.dp)
        )

        // YouTube URL Input
        OutlinedTextField(
            value = youtubeUrl,
            onValueChange = {
                youtubeUrl = it
                errorMessage = null // Clear error when user types
            },
            label = { Text("YouTube URL") },
            placeholder = { Text(stringResource(R.string.youtube_url_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground
            )
        )

        // Error Message
        errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Start Workout Button
        Button(
            onClick = {
                val trimmedUrl = youtubeUrl.trim()
                when {
                    trimmedUrl.isEmpty() -> {
                        errorMessage = "Please enter a YouTube URL"
                    }
                    else -> {
                        isValidating = true
                        errorMessage = null

                        coroutineScope.launch {
                            try {
                                val isValid = withTimeout(2000) { // 2 second timeout
                                    YouTubeUrlValidator.isValidYouTubeUrl(trimmedUrl)
                                }

                                if (isValid) {
                                    onStartWorkout(trimmedUrl)
                                } else {
                                    errorMessage = "Please enter a valid YouTube URL"
                                }
                            } catch (e: Exception) {
                                errorMessage = "Error validating URL. Please try again."
                            } finally {
                                isValidating = false
                            }
                        }
                    }
                }
            },
            enabled = !isValidating,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(50.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (isValidating) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Validating...",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.start_workout_button),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    FitnessMirrorNativeTheme {
        HomeScreen(
            onStartWorkout = { }
        )
    }
}