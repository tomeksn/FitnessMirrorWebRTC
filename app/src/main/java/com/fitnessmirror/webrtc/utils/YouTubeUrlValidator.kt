package com.fitnessmirror.webrtc.utils

import java.util.regex.Pattern

object YouTubeUrlValidator {

    private val youTubePatterns = listOf(
        // Standard YouTube URL patterns
        Pattern.compile("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|youtube\\.com/v/)([^&\\n?#]+)"),
        Pattern.compile("(?:youtube\\.com/watch\\?.*v=)([^&\\n?#]+)"),
        // YouTube Shorts
        Pattern.compile("(?:youtube\\.com/shorts/)([^&\\n?#]+)"),
        // Mobile YouTube URLs
        Pattern.compile("(?:m\\.youtube\\.com/watch\\?v=)([^&\\n?#]+)")
    )

    /**
     * Validates if the provided URL is a valid YouTube URL
     * @param url The URL to validate
     * @return true if valid YouTube URL, false otherwise
     */
    fun isValidYouTubeUrl(url: String): Boolean {
        if (url.isBlank()) return false

        return youTubePatterns.any { pattern ->
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                val videoId = matcher.group(1)
                // Simple validation: video ID should be exactly 11 chars and contain valid chars
                isValidVideoId(videoId)
            } else {
                false
            }
        }
    }

    /**
     * Fast validation of YouTube video ID without regex
     * @param videoId The video ID to validate
     * @return true if valid video ID format
     */
    private fun isValidVideoId(videoId: String?): Boolean {
        if (videoId == null || videoId.length != 11) return false

        // Check each character is alphanumeric, underscore, or dash
        for (char in videoId) {
            if (!char.isLetterOrDigit() && char != '_' && char != '-') {
                return false
            }
        }
        return true
    }

    /**
     * Extracts YouTube video ID from a valid YouTube URL
     * @param url The YouTube URL
     * @return Video ID if valid, null otherwise
     */
    fun extractVideoId(url: String): String? {
        if (url.isBlank()) return null

        youTubePatterns.forEach { pattern ->
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                val videoId = matcher.group(1)
                // Validate video ID format using fast validation
                if (isValidVideoId(videoId)) {
                    return videoId
                }
            }
        }

        return null
    }

    /**
     * Creates an embed URL from a YouTube URL
     * @param url The original YouTube URL
     * @return Embed URL if valid, null otherwise
     */
    fun createEmbedUrl(url: String): String? {
        val videoId = extractVideoId(url)
        return if (videoId != null) {
            // Use nocookie domain for better embed compatibility
            "https://www.youtube-nocookie.com/embed/$videoId?enablejsapi=1&rel=0&showinfo=0&controls=1&autoplay=0&mute=0&loop=0&playsinline=1&origin=android-app://com.fitnessmirror.app&widget_referrer=android-app://com.fitnessmirror.app"
        } else {
            null
        }
    }
}