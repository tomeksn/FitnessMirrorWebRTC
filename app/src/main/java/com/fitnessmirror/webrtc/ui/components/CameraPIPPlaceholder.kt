package com.fitnessmirror.webrtc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fitnessmirror.webrtc.ui.theme.FitnessMirrorNativeTheme

@Composable
fun CameraPIPPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .border(
                width = 2.dp,
                color = Color.White,
                shape = RoundedCornerShape(10.dp)
            ),
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Camera PIP",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "TODO: Integrate",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Yellow
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CameraPIPPlaceholderPreview() {
    FitnessMirrorNativeTheme {
        CameraPIPPlaceholder(
            modifier = Modifier.size(120.dp, 160.dp)
        )
    }
}