package com.fitnessmirror.webrtc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fitnessmirror.webrtc.R
import com.fitnessmirror.webrtc.ui.theme.FitnessGreen
import com.fitnessmirror.webrtc.ui.theme.FitnessOrange
import com.fitnessmirror.webrtc.ui.theme.FitnessMirrorNativeTheme

@Composable
fun ConnectionStatusPanel(
    isStreaming: Boolean,
    serverAddress: String?,
    hasConnectedClient: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Gray.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Status Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Status Indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                isStreaming && hasConnectedClient -> FitnessGreen
                                isStreaming -> FitnessOrange
                                else -> Color.Gray
                            }
                        )
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = when {
                        isStreaming && hasConnectedClient -> stringResource(R.string.status_streaming)
                        isStreaming -> "Streaming (No clients)"
                        else -> stringResource(R.string.status_ready)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Server Address (if streaming)
            if (isStreaming && serverAddress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.server_address_format, serverAddress),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // Connected Clients Count
            if (isStreaming) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.connected_clients_format,
                        if (hasConnectedClient) 1 else 0
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // Instructions
            if (isStreaming && !hasConnectedClient) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📱 Open TV browser and navigate to the address above",
                    style = MaterialTheme.typography.bodySmall,
                    color = FitnessOrange
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusPanelPreview() {
    FitnessMirrorNativeTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConnectionStatusPanel(
                isStreaming = false,
                serverAddress = null,
                hasConnectedClient = false
            )

            ConnectionStatusPanel(
                isStreaming = true,
                serverAddress = "192.168.1.100:8080",
                hasConnectedClient = false
            )

            ConnectionStatusPanel(
                isStreaming = true,
                serverAddress = "192.168.1.100:8080",
                hasConnectedClient = true
            )
        }
    }
}