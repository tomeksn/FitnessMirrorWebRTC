package com.fitnessmirror.webrtc.streaming

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import com.fitnessmirror.webrtc.network.NetworkUtils
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class StreamingServer(
    private val port: Int = 8080,
    private val callback: StreamingCallback,
    private val context: Context
) : NanoWSD(port) {

    companion object {
        private const val TAG = "StreamingServer"
        private const val WEBSOCKET_PATH = "/stream"
        private const val SOCKET_TIMEOUT_MS = 120000 // 120 seconds - increased for TV compatibility
        private const val PING_INTERVAL_MS = 60000L // 60 seconds ping interval - reduced frequency

        // Custom close codes for better client handling
        private const val CLOSE_CODE_STREAMING_STOPPED = 4000
        private const val CLOSE_CODE_SERVER_SHUTDOWN = 4001
    }

    init {
        // Socket timeout configuration moved to companion object
    }

    interface StreamingCallback {
        fun onClientConnected()
        fun onClientDisconnected()
        fun onServerStarted(port: Int)
        fun onServerError(error: String)
        // WebRTC signaling callbacks
        fun onWebRTCOffer(sdp: SessionDescription)
        fun onWebRTCAnswer(sdp: SessionDescription)
        fun onWebRTCIceCandidate(candidate: IceCandidate)
    }

    private var currentWebSocket: StreamingWebSocket? = null
    private var isRunning = false
    private var pingTimer: java.util.Timer? = null
    private var lastFrameData: ByteArray? = null
    private val sseClients = mutableListOf<SSEClient>()
    private var isFrontCamera: Boolean = true  // Track current camera facing for mirror effect

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val headers = session.headers
        val remoteIp = session.remoteIpAddress

        Log.d(TAG, "=== HTTP Request ===")
        Log.d(TAG, "URI: $uri")
        Log.d(TAG, "Method: $method")
        Log.d(TAG, "Remote IP: $remoteIp")
        Log.d(TAG, "Headers: $headers")
        Log.d(TAG, "User-Agent: ${headers["user-agent"]}")
        Log.d(TAG, "===================")

        return when {
            uri == "/" || uri == "/index.html" -> {
                Log.d(TAG, "Serving web client HTML to $remoteIp")
                val htmlContent = getWebClientHtml()
                val response = newFixedLengthResponse(Response.Status.OK, "text/html", htmlContent)
                response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                response.addHeader("Pragma", "no-cache")
                response.addHeader("Expires", "0")
                response.addHeader("ETag", "\"mirror-fix-${System.currentTimeMillis()}\"") // Force TV cache refresh
                response
            }
            uri == "/test" -> {
                Log.d(TAG, "Serving test page to $remoteIp")
                val testContent = getTestPageHtml()
                newFixedLengthResponse(Response.Status.OK, "text/html", testContent)
            }
            uri == "/debug" -> {
                Log.d(TAG, "Serving debug page to $remoteIp")
                val debugContent = getDebugPageHtml(remoteIp, headers)
                newFixedLengthResponse(Response.Status.OK, "text/html", debugContent)
            }
            uri == "/api/status" -> {
                Log.d(TAG, "API status request from $remoteIp")
                val status = """{"status":"ok","clients":${if(hasConnectedClient()) 1 else 0},"uptime":"${System.currentTimeMillis()}"}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", status)
            }
            uri == "/webrtc-offer" && method == Method.POST -> {
                Log.d(TAG, "WebRTC offer received from $remoteIp")
                handleWebRTCOffer(session)
            }
            uri == "/webrtc-answer" && method == Method.POST -> {
                Log.d(TAG, "WebRTC answer received from $remoteIp")
                handleWebRTCAnswer(session)
            }
            uri == "/webrtc-ice" && method == Method.POST -> {
                Log.d(TAG, "WebRTC ICE candidate received from $remoteIp")
                handleWebRTCIceCandidate(session)
            }
            uri == "/stream-sse" -> {
                Log.d(TAG, "Server-Sent Events stream request from $remoteIp")
                handleSSEStream(session)
            }
            uri == "/fallback" -> {
                Log.d(TAG, "Serving fallback client to $remoteIp")
                val fallbackContent = getFallbackClientHtml()
                newFixedLengthResponse(Response.Status.OK, "text/html", fallbackContent)
            }
            uri == WEBSOCKET_PATH -> {
                Log.d(TAG, "WebSocket upgrade request from $remoteIp")
                Log.d(TAG, "WebSocket headers: ${headers.filter { it.key.contains("websocket", true) || it.key.contains("upgrade", true) || it.key.contains("connection", true) }}")
                super.serve(session)
            }
            else -> {
                Log.w(TAG, "404 Not Found: $uri from $remoteIp")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: $uri")
            }
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val remoteIp = handshake.remoteIpAddress
        val userAgent = handshake.headers["user-agent"] ?: "Unknown"
        Log.d(TAG, "=== WebSocket Connection ===")
        Log.d(TAG, "Remote IP: $remoteIp")
        Log.d(TAG, "User-Agent: $userAgent")
        Log.d(TAG, "Headers: ${handshake.headers}")
        Log.d(TAG, "Current client: ${currentWebSocket != null}")
        Log.d(TAG, "=========================")
        return StreamingWebSocket(handshake, this)
    }

    fun startServer() {
        try {
            Log.d(TAG, "Starting server on port $port...")
            Log.d(TAG, "Socket timeout: ${SOCKET_TIMEOUT_MS}ms")
            Log.d(TAG, "Ping interval: ${PING_INTERVAL_MS}ms")

            // Explicitly bind to all interfaces for TV compatibility
            start(SOCKET_TIMEOUT_MS, false)
            isRunning = true

            Log.i(TAG, "✅ Streaming server started successfully!")
            Log.i(TAG, "📡 Server listening on: 0.0.0.0:$port (all interfaces)")
            Log.i(TAG, "🌐 WebSocket endpoint: $WEBSOCKET_PATH")
            Log.i(TAG, "🔄 SSE endpoint: /stream-sse")
            Log.i(TAG, "🔧 Fallback client: /fallback")
            Log.i(TAG, "🧪 Test page: /test")
            Log.i(TAG, "🔍 Debug page: /debug")
            Log.i(TAG, "⏱️ Socket timeout: ${SOCKET_TIMEOUT_MS}ms")
            Log.i(TAG, "💓 Ping interval: ${PING_INTERVAL_MS}ms")

            callback.onServerStarted(port)
        } catch (e: IOException) {
            Log.e(TAG, "❌ Failed to start server on port $port", e)
            Log.e(TAG, "Error details: ${e.message}")
            Log.e(TAG, "Error cause: ${e.cause}")

            // Try alternative ports if the main port fails
            if (port == 8080) {
                Log.i(TAG, "💡 Tip: If port 8080 is busy, try using a different port in MainActivity")
                Log.i(TAG, "💡 Alternative ports for TV: 8000, 3000, 8888")
            }

            callback.onServerError("Failed to start server: ${e.message}")
        }
    }

    fun stopServer() {
        if (isRunning) {
            // Stop ping timer FIRST to prevent sending pings to closed connection
            stopPingTimer()

            // Send custom close code to inform client that streaming is intentionally stopped
            currentWebSocket?.let { socket ->
                try {
                    socket.close(
                        NanoWSD.WebSocketFrame.CloseCode.UnsupportedData, // Using as custom code container
                        "STREAMING_STOPPED", // Custom reason
                        false
                    )
                    Log.d(TAG, "Sent streaming stopped signal to client")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send close signal to client", e)
                }
            }
            currentWebSocket = null

            // Finally stop the server
            stop()
            isRunning = false
            Log.d(TAG, "Streaming server stopped gracefully")
        }
    }

    fun broadcastFrame(jpegData: ByteArray) {
        lastFrameData = jpegData

        // Send to WebSocket clients with enhanced error handling
        currentWebSocket?.let { socket ->
            try {
                // Send timestamp first for latency measurement
                val timestamp = System.currentTimeMillis()
                val timestampMessage = """{"type":"TIMESTAMP","timestamp":$timestamp}"""
                socket.send(timestampMessage)

                // Then send binary frame
                socket.send(jpegData)
            } catch (e: IOException) {
                // Enhanced error classification for frame sending
                when {
                    e.message?.contains("Software caused connection abort") == true -> {
                        Log.d(TAG, "WebSocket frame send failed - client disconnected gracefully")
                    }
                    e.message?.contains("Broken pipe") == true -> {
                        Log.d(TAG, "WebSocket frame send failed - connection broken")
                    }
                    e.message?.contains("Connection reset") == true -> {
                        Log.d(TAG, "WebSocket frame send failed - connection reset")
                    }
                    else -> {
                        Log.w(TAG, "Failed to send frame to WebSocket: ${e.message}")
                    }
                }
                handleClientDisconnected()
            }
        }

        // Send to SSE clients with improved error handling
        synchronized(sseClients) {
            val iterator = sseClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    val base64Data = android.util.Base64.encodeToString(jpegData, android.util.Base64.NO_WRAP)
                    client.sendFrame(base64Data)
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to send frame to SSE client, removing: ${e.message}")
                    iterator.remove()
                }
            }
        }
    }

    fun sendYouTubeVideoInfo(videoId: String, currentTime: Float = 0f) {
        val message = """{"type":"VIDEO_URL","videoId":"$videoId","currentTime":$currentTime}"""

        currentWebSocket?.let { socket ->
            try {
                socket.send(message)
                Log.d(TAG, "Sent YouTube video info: $videoId at ${currentTime}s")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send YouTube video info", e)
            }
        }
    }

    fun sendVideoCommand(command: String, value: Float? = null) {
        val message = if (value != null) {
            """{"type":"VIDEO_CONTROL","command":"$command","value":$value}"""
        } else {
            """{"type":"VIDEO_CONTROL","command":"$command"}"""
        }

        currentWebSocket?.let { socket ->
            try {
                socket.send(message)
                Log.d(TAG, "Sent video command: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send video command", e)
            }
        }
    }

    fun hasConnectedClient(): Boolean {
        return currentWebSocket != null || sseClients.isNotEmpty()
    }

    fun updateCameraFacing(isFrontCamera: Boolean) {
        this.isFrontCamera = isFrontCamera
        Log.d(TAG, "Camera facing updated: ${if (isFrontCamera) "FRONT" else "BACK"}")
        // TODO: Optionally notify TV client via WebSocket to update mirror in real-time
    }

    private fun handleSSEStream(session: IHTTPSession): Response {
        Log.d(TAG, "Creating SSE stream for ${session.remoteIpAddress}")

        return object : Response(Response.Status.OK, "text/event-stream", null as java.io.InputStream?, -1) {
            override fun send(outputStream: java.io.OutputStream) {
                try {
                    val writer = java.io.OutputStreamWriter(outputStream, "UTF-8")

                    // Send SSE headers
                    sendLine(outputStream, "HTTP/1.1 200 OK")
                    sendLine(outputStream, "Content-Type: text/event-stream")
                    sendLine(outputStream, "Cache-Control: no-cache")
                    sendLine(outputStream, "Connection: keep-alive")
                    sendLine(outputStream, "Access-Control-Allow-Origin: *")
                    sendLine(outputStream, "")

                    val sseClient = SSEClient(writer, outputStream)
                    synchronized(sseClients) {
                        sseClients.add(sseClient)
                    }

                    Log.d(TAG, "SSE client connected. Total SSE clients: ${sseClients.size}")

                    // Send initial connection event
                    sseClient.sendEvent("connected", "SSE stream established")

                    // Send last frame if available
                    lastFrameData?.let {
                        val base64Data = android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
                        sseClient.sendFrame(base64Data)
                    }

                    // Keep connection alive
                    try {
                        while (sseClients.contains(sseClient)) {
                            Thread.sleep(1000)
                            sseClient.sendEvent("ping", "alive")
                        }
                    } catch (e: InterruptedException) {
                        Log.d(TAG, "SSE connection interrupted")
                    } catch (e: Exception) {
                        Log.w(TAG, "SSE connection error", e)
                    } finally {
                        synchronized(sseClients) {
                            sseClients.remove(sseClient)
                        }
                        Log.d(TAG, "SSE client disconnected. Remaining: ${sseClients.size}")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "SSE stream error", e)
                }
            }

            private fun sendLine(outputStream: java.io.OutputStream, line: String) {
                outputStream.write((line + "\r\n").toByteArray())
            }
        }
    }

    internal fun handleClientConnected(webSocket: StreamingWebSocket) {
        Log.i(TAG, "=== New Client Connection ===")

        // Only allow one client at a time
        if (currentWebSocket != null) {
            Log.w(TAG, "Disconnecting existing client for new connection")
            currentWebSocket?.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "New client connected", false)
        }

        currentWebSocket = webSocket
        startPingTimer()

        Log.i(TAG, "✅ Client connected successfully!")
        Log.i(TAG, "💓 Ping timer started (${PING_INTERVAL_MS}ms interval)")
        Log.i(TAG, "===========================")

        callback.onClientConnected()
    }

    internal fun handleClientDisconnected() {
        Log.i(TAG, "=== Client Disconnection ===")
        Log.i(TAG, "🔌 Client disconnected")

        currentWebSocket = null
        stopPingTimer()

        Log.i(TAG, "💓 Ping timer stopped")
        Log.i(TAG, "=========================")

        callback.onClientDisconnected()
    }

    private fun startPingTimer() {
        stopPingTimer() // Stop any existing timer
        pingTimer = java.util.Timer("WebSocketPing", true)
        pingTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                // Safety checks before sending ping
                if (!isRunning) {
                    Log.d(TAG, "Server not running, canceling ping timer")
                    cancel()
                    return
                }

                currentWebSocket?.let { socket ->
                    try {
                        Log.d(TAG, "Sending WebSocket ping")
                        socket.ping("ping".toByteArray())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send ping, client may have disconnected", e)
                        // Don't call handleClientDisconnected() from timer thread
                        // Let the WebSocket's onException handle it
                        cancel()
                    }
                }
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS)
    }

    private fun stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = null
    }

    private fun getTestPageHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CastApp - Connection Test</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            margin: 20px;
            background: #f0f0f0;
        }
        .test-box {
            background: white;
            padding: 20px;
            margin: 10px 0;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .success { border-left: 4px solid #4CAF50; }
        .error { border-left: 4px solid #f44336; }
        .warning { border-left: 4px solid #ff9800; }
        button {
            background: #4CAF50;
            color: white;
            border: none;
            padding: 10px 20px;
            border-radius: 4px;
            cursor: pointer;
            margin: 5px;
        }
        button:hover { background: #45a049; }
        #log {
            background: #000;
            color: #0f0;
            padding: 10px;
            border-radius: 4px;
            font-family: monospace;
            max-height: 300px;
            overflow-y: auto;
            white-space: pre-wrap;
        }
    </style>
</head>
<body>
    <h1>📱➡️📺 CastApp Connection Test</h1>

    <div class="test-box success">
        <h3>✅ Basic HTTP Connection - OK</h3>
        <p>Your device can successfully connect to the CastApp server!</p>
    </div>

    <div class="test-box" id="websocket-test">
        <h3>🔗 WebSocket Test</h3>
        <button onclick="testWebSocket()">Test WebSocket Connection</button>
        <button onclick="testSimpleWebSocket()">Test Simple WebSocket</button>
        <div id="ws-status">Click button to test WebSocket connection...</div>
    </div>

    <div class="test-box">
        <h3>📊 Browser Information</h3>
        <div id="browser-info"></div>
    </div>

    <div class="test-box">
        <h3>📝 Connection Log</h3>
        <button onclick="clearLog()">Clear Log</button>
        <div id="log"></div>
    </div>

    <script>
        function log(message) {
            const logDiv = document.getElementById('log');
            const timestamp = new Date().toLocaleTimeString();
            logDiv.textContent += '[' + timestamp + '] ' + message + '\\n';
            logDiv.scrollTop = logDiv.scrollHeight;
        }

        function testWebSocket() {
            const wsUrl = (window.location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + window.location.host + '/stream';
            log('Testing WebSocket: ' + wsUrl);

            const ws = new WebSocket(wsUrl);
            ws.binaryType = 'arraybuffer';

            const timeout = setTimeout(() => {
                log('❌ WebSocket connection timeout (10s)');
                ws.close();
                updateWSStatus('❌ Connection timeout', 'error');
            }, 10000);

            ws.onopen = () => {
                clearTimeout(timeout);
                log('✅ WebSocket connected successfully!');
                updateWSStatus('✅ WebSocket Connected', 'success');
                setTimeout(() => ws.close(), 2000);
            };

            ws.onclose = (event) => {
                clearTimeout(timeout);
                log('🔌 WebSocket closed: ' + event.code + ' - ' + event.reason);
            };

            ws.onerror = (error) => {
                clearTimeout(timeout);
                log('❌ WebSocket error: ' + error);
                updateWSStatus('❌ WebSocket Error', 'error');
            };
        }

        function testSimpleWebSocket() {
            log('Testing simple WebSocket without binary type...');
            const wsUrl = (window.location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + window.location.host + '/stream';

            const ws = new WebSocket(wsUrl);
            // Don't set binaryType for compatibility

            const timeout = setTimeout(() => {
                log('❌ Simple WebSocket timeout');
                ws.close();
            }, 10000);

            ws.onopen = () => {
                clearTimeout(timeout);
                log('✅ Simple WebSocket connected!');
                updateWSStatus('✅ Simple WebSocket Works', 'success');
                setTimeout(() => ws.close(), 2000);
            };

            ws.onclose = (event) => {
                clearTimeout(timeout);
                log('🔌 Simple WebSocket closed: ' + event.code);
            };

            ws.onerror = (error) => {
                clearTimeout(timeout);
                log('❌ Simple WebSocket error');
                updateWSStatus('❌ Simple WebSocket Failed', 'error');
            };
        }

        function updateWSStatus(message, type) {
            const statusDiv = document.getElementById('ws-status');
            const testBox = document.getElementById('websocket-test');
            statusDiv.textContent = message;
            testBox.className = 'test-box ' + type;
        }

        function clearLog() {
            document.getElementById('log').textContent = '';
        }

        // Show browser info
        document.getElementById('browser-info').innerHTML =
            '<strong>User Agent:</strong> ' + navigator.userAgent + '<br>' +
            '<strong>Platform:</strong> ' + navigator.platform + '<br>' +
            '<strong>Language:</strong> ' + navigator.language + '<br>' +
            '<strong>WebSocket Support:</strong> ' + (typeof WebSocket !== 'undefined' ? '✅ Yes' : '❌ No') + '<br>' +
            '<strong>Canvas Support:</strong> ' + (typeof HTMLCanvasElement !== 'undefined' ? '✅ Yes' : '❌ No') + '<br>' +
            '<strong>Blob Support:</strong> ' + (typeof Blob !== 'undefined' ? '✅ Yes' : '❌ No');

        log('🚀 Test page loaded');
        log('📍 Current URL: ' + window.location.href);
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun getDebugPageHtml(remoteIp: String, headers: Map<String, String>): String {
        val serverInfo = """
Server IP: ${NetworkUtils.getLocalIpAddress(context) ?: "Unknown"}
Server Port: $port
Client IP: $remoteIp
Connected Clients: ${if (hasConnectedClient()) 1 else 0}
Server Running: $isRunning
Socket Timeout: ${SOCKET_TIMEOUT_MS}ms
Ping Interval: ${PING_INTERVAL_MS}ms
Current Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}
        """.trimIndent()

        val clientHeaders = headers.entries.joinToString("\\n") { "${it.key}: ${it.value}" }

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>CastApp Debug Info</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }
        .debug-section { background: white; margin: 10px 0; padding: 15px; border-radius: 8px; }
        .debug-section h3 { margin-top: 0; color: #333; }
        pre { background: #000; color: #0f0; padding: 10px; border-radius: 4px; font-size: 12px; overflow-x: auto; }
        .status-ok { color: #4CAF50; font-weight: bold; }
        .status-error { color: #f44336; font-weight: bold; }
    </style>
</head>
<body>
    <h1>🔧 CastApp Debug Information</h1>

    <div class="debug-section">
        <h3>📊 Server Status</h3>
        <pre>$serverInfo</pre>
    </div>

    <div class="debug-section">
        <h3>📱 Client Information</h3>
        <pre>$clientHeaders</pre>
    </div>

    <div class="debug-section">
        <h3>🔗 Available Endpoints</h3>
        <ul>
            <li><a href="/">/ - Main streaming client (WebSocket)</a></li>
            <li><a href="/test">⭐ /test - Connection test page</a></li>
            <li><a href="/fallback">🔧 /fallback - SSE fallback client</a></li>
            <li><a href="/debug">/debug - This debug page</a></li>
            <li>/stream - WebSocket endpoint</li>
            <li>/stream-sse - Server-Sent Events endpoint</li>
            <li>/api/status - JSON status API</li>
        </ul>
    </div>

    <div class="debug-section">
        <h3>💡 Troubleshooting Tips for TV Connection Issues</h3>
        <ul>
            <li><strong>Step 1:</strong> Try the <a href="/test"><strong>/test</strong></a> page first to check WebSocket compatibility</li>
            <li><strong>Step 2:</strong> If WebSocket fails, try the <a href="/fallback"><strong>/fallback</strong></a> client (uses Server-Sent Events)</li>
            <li><strong>Network:</strong> Make sure both devices are on the same WiFi network (not guest network)</li>
            <li><strong>Browser:</strong> Check if your TV browser supports WebSocket (most modern ones do)</li>
            <li><strong>Connection:</strong> Try refreshing the page if connection fails</li>
            <li><strong>Ports:</strong> Some Smart TVs work better with specific ports (8080, 8000, 3000)</li>
            <li><strong>Alternative:</strong> If all else fails, access the stream from phone browser first to verify it works</li>
        </ul>

        <h4>🚨 Common TV Browser Issues:</h4>
        <ul>
            <li><strong>Samsung TV:</strong> Try /fallback endpoint if main client fails</li>
            <li><strong>LG WebOS:</strong> Usually works with WebSocket, try refreshing</li>
            <li><strong>Android TV:</strong> Should work with main client</li>
            <li><strong>Older TVs:</strong> Use /fallback client with Server-Sent Events</li>
        </ul>
    </div>
</body>
</html>
        """.trimIndent()
    }

    private fun getFallbackClientHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CastApp - Fallback Client</title>
    <style>
        body {
            margin: 0;
            padding: 0;
            background: black;
            overflow: hidden;
            font-family: Arial, sans-serif;
        }
        #stream-container {
            position: fixed;
            bottom: 20px;
            right: 20px;
            width: 20vw;
            max-width: 320px;
            min-width: 200px;
            border: 2px solid #fff;
            border-radius: 8px;
            background: #000;
        }
        #stream {
            width: 100%;
            height: auto;
            display: block;
        }
        /* Mirror effect for front camera (natural selfie view) */
        #stream.mirror {
            transform: scaleX(-1);
        }
        #status {
            position: absolute;
            top: 10px;
            left: 10px;
            color: white;
            background: rgba(0,0,0,0.7);
            padding: 5px 10px;
            border-radius: 4px;
            font-size: 12px;
        }
        .status-connected { color: #4CAF50; }
        .status-disconnected { color: #f44336; }
        .status-connecting { color: #ff9800; }
    </style>
</head>
<body>
    <div id="status" class="status-connecting">Connecting via SSE...</div>
    <div id="stream-container">
        <img id="stream" src="data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMzIwIiBoZWlnaHQ9IjI0MCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSIjMzMzIi8+PHRleHQgeD0iNTAlIiB5PSI1MCUiIGZvbnQtZmFtaWx5PSJBcmlhbCIgZm9udC1zaXplPSIxNiIgZmlsbD0iI2ZmZiIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZHk9Ii4zZW0iPldhaXRpbmcgZm9yIHN0cmVhbS4uLjwvdGV4dD48L3N2Zz4=" alt="Loading...">
    </div>

    <script>
        function FallbackReceiver() {
            this.img = document.getElementById('stream');
            this.status = document.getElementById('status');
            this.eventSource = null;
            this.reconnectAttempts = 0;
            this.maxReconnectAttempts = 20;
            this.reconnectDelay = 3000;

            // Mirror effect for front camera (default)
            // TODO: Make dynamic via WebSocket message when camera switches
            this.isFrontCamera = true;
            if (this.isFrontCamera) {
                this.img.classList.add('mirror');
                console.log('📷 Front camera: Mirror effect enabled (SSE mode)');
            }

            this.connect();
        }

        FallbackReceiver.prototype.connect = function() {
            var self = this;
            var sseUrl = window.location.protocol + '//' + window.location.host + '/stream-sse';

            console.log('Connecting to SSE: ' + sseUrl);
            this.updateStatus('Connecting via SSE...', 'connecting');

            try {
                this.eventSource = new EventSource(sseUrl);

                this.eventSource.onopen = function() {
                    console.log('SSE connected');
                    self.updateStatus('Connected - SSE Mode', 'connected');
                    self.reconnectAttempts = 0;
                };

                this.eventSource.onmessage = function(event) {
                    try {
                        var data = JSON.parse(event.data);
                        if (data.type === 'frame' && data.data) {
                            self.img.src = 'data:image/jpeg;base64,' + data.data;
                        }
                    } catch (e) {
                        console.log('SSE message:', event.data);
                    }
                };

                this.eventSource.onerror = function(error) {
                    console.error('SSE error:', error);
                    self.updateStatus('Connection Error', 'disconnected');
                    self.eventSource.close();
                    self.scheduleReconnect();
                };

            } catch (error) {
                console.error('Failed to create EventSource:', error);
                this.updateStatus('SSE Not Supported', 'disconnected');
            }
        };

        FallbackReceiver.prototype.updateStatus = function(message, type) {
            this.status.textContent = message;
            this.status.className = 'status-' + type;
        };

        FallbackReceiver.prototype.scheduleReconnect = function() {
            var self = this;
            if (this.reconnectAttempts < this.maxReconnectAttempts) {
                this.reconnectAttempts++;
                var delay = this.reconnectDelay * this.reconnectAttempts;
                if (delay > 15000) delay = 15000;

                console.log('Reconnecting SSE in ' + delay + 'ms');
                this.updateStatus('Reconnecting in ' + Math.ceil(delay/1000) + 's...', 'connecting');

                setTimeout(function() {
                    self.connect();
                }, delay);
            } else {
                this.updateStatus('Connection Failed', 'disconnected');
            }
        };

        // Initialize
        var receiver = new FallbackReceiver();
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun getWebClientHtml(): String {
        return """
<!DOCTYPE html>
<html>
<!-- Mirror Effect Fix: ${System.currentTimeMillis()} - Unified mirror at camera frame level -->
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>FitnessMirror - Workout Stream</title>
    <style>
        body {
            margin: 0;
            padding: 0;
            background: black;
            overflow: hidden;
            font-family: Arial, sans-serif;
        }

        /* YouTube player takes up full screen */
        #youtube-container {
            position: absolute;
            top: 0;
            left: 0;
            width: 100vw;
            height: 100vh;
            background: black;
        }

        #youtube-player {
            width: 100%;
            height: 100%;
            border: none;
        }

        /* Camera stream overlay in bottom-right corner */
        #stream-container {
            position: fixed;
            bottom: 20px;
            right: 20px;
            width: 25vw;
            max-width: 400px;
            min-width: 250px;
            border: 3px solid #fff;
            border-radius: 12px;
            background: #000;
            box-shadow: 0 6px 20px rgba(0,0,0,0.8);
            z-index: 1000;
        }

        #stream {
            width: 100%;
            height: auto;
            display: block;
            border-radius: 9px;
        }

        /* Mirror effect for front camera (natural selfie view) */
        #stream.mirror {
            transform: scaleX(-1);
        }

        /* Status indicator */
        #status {
            position: fixed;
            top: 20px;
            left: 20px;
            color: white;
            background: rgba(0,0,0,0.8);
            padding: 8px 16px;
            border-radius: 6px;
            font-size: 14px;
            z-index: 1001;
            border: 1px solid rgba(255,255,255,0.2);
        }

        .status-connected { color: #4CAF50; }
        .status-disconnected { color: #f44336; }
        .status-connecting { color: #ff9800; }

        /* Welcome message when no video is loaded */
        #welcome-screen {
            position: absolute;
            top: 0;
            left: 0;
            width: 100vw;
            height: 100vh;
            background: linear-gradient(135deg, #1a1a1a, #2d2d2d);
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            text-align: center;
            color: white;
        }

        .welcome-title {
            font-size: 3em;
            font-weight: bold;
            margin-bottom: 20px;
            background: linear-gradient(45deg, #ff6b6b, #4ecdc4);
            background-clip: text;
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }

        .welcome-subtitle {
            font-size: 1.5em;
            color: #aaa;
            margin-bottom: 40px;
        }

        .loading-dots {
            display: inline-block;
            animation: dots 2s infinite;
        }

        @keyframes dots {
            0%, 20% { color: transparent; text-shadow: .25em 0 0 transparent, .5em 0 0 transparent; }
            40% { color: white; text-shadow: .25em 0 0 transparent, .5em 0 0 transparent; }
            60% { text-shadow: .25em 0 0 white, .5em 0 0 transparent; }
            80%, 100% { text-shadow: .25em 0 0 white, .5em 0 0 white; }
        }

        .hidden { display: none !important; }

        @media (max-width: 768px) {
            #stream-container {
                width: 35vw;
                bottom: 15px;
                right: 15px;
                min-width: 200px;
            }

            .welcome-title {
                font-size: 2em;
            }

            .welcome-subtitle {
                font-size: 1.2em;
            }
        }
    </style>
</head>
<body>
    <!-- Status indicator -->
    <div id="status" class="status-connecting">Connecting<span class="loading-dots">...</span></div>

    <!-- Welcome screen (shown when no video is playing) -->
    <div id="welcome-screen">
        <div class="welcome-title">🏋️ FitnessMirror</div>
        <div class="welcome-subtitle">Waiting for workout video<span class="loading-dots">...</span></div>
    </div>

    <!-- YouTube video player (hidden initially) -->
    <div id="youtube-container" class="hidden">
        <iframe id="youtube-player"
                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                allowfullscreen>
        </iframe>
    </div>

    <!-- Camera stream overlay -->
    <div id="stream-container">
        <img id="stream" src="" alt="Camera stream">
    </div>

    <script>
        // Helper function to filter VP8 codec from SDP
        // Forces H.264 which has better hardware support on TVs
        function filterVp8FromSdp(sdp) {
            var lines = sdp.split('\r\n');
            var vp8PayloadType = null;

            // Find VP8 payload type
            for (var i = 0; i < lines.length; i++) {
                var match = lines[i].match(/a=rtpmap:(\d+) VP8\/90000/);
                if (match) {
                    vp8PayloadType = match[1];
                    console.log('Found VP8 payload type to filter: ' + vp8PayloadType);
                    break;
                }
            }

            if (!vp8PayloadType) {
                console.log('No VP8 codec found in SDP, returning unchanged');
                return sdp;  // No VP8 found, return unchanged
            }

            // Filter VP8 lines
            var filteredLines = [];
            for (var i = 0; i < lines.length; i++) {
                var line = lines[i];
                var skipLine = (
                    line.indexOf('a=rtpmap:' + vp8PayloadType + ' ') !== -1 ||
                    line.indexOf('a=rtcp-fb:' + vp8PayloadType + ' ') !== -1 ||
                    line.indexOf('a=fmtp:' + vp8PayloadType + ' ') !== -1
                );

                if (!skipLine) {
                    if (line.indexOf('m=video') === 0) {
                        // Remove VP8 from m= line
                        line = line.replace(' ' + vp8PayloadType, '');
                    }
                    filteredLines.push(line);
                }
            }

            console.log('SDP filtered - VP8 codec removed, forcing H.264');
            return filteredLines.join('\r\n');
        }

        // WebRTC Receiver for low-latency streaming
        function WebRTCReceiver() {
            console.log('🎥 Initializing WebRTC receiver...');

            this.video = document.getElementById('stream');
            // Change img to video element for WebRTC
            if (this.video.tagName === 'IMG') {
                var newVideo = document.createElement('video');
                newVideo.id = 'stream';
                newVideo.className = this.video.className;
                newVideo.autoplay = true;
                newVideo.playsinline = true;
                newVideo.muted = true;
                this.video.parentNode.replaceChild(newVideo, this.video);
                this.video = newVideo;
            }

            this.status = document.getElementById('status');
            this.pc = null;
            this.ws = null;  // WebSocket for signaling only
            this.isFrontCamera = true;

            // Latency tracking variables
            this.lastFrameTimestamp = 0;
            this.latencySamples = [];
            this.maxLatencySamples = 10;
            this.frameCount = 0;

            // Apply mirror effect for front camera
            if (this.isFrontCamera) {
                this.video.classList.add('mirror');
                console.log('📷 Front camera: Mirror effect enabled');
            }

            this.init();
        }

        WebRTCReceiver.prototype.init = function() {
            var self = this;

            // Create RTCPeerConnection
            var config = {
                iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
            };

            try {
                this.pc = new RTCPeerConnection(config);
                console.log('✅ RTCPeerConnection created');

                // Handle incoming video track
                this.pc.ontrack = function(event) {
                    console.log('✅ Received remote video track');
                    self.video.srcObject = event.streams[0];

                    // Start frame counter and latency monitoring
                    self.startLatencyMonitoring();

                    self.updateStatus('WebRTC Connected', 'connected');
                };

                // Handle ICE candidates
                this.pc.onicecandidate = function(event) {
                    if (event.candidate) {
                        console.log('📡 Sending ICE candidate to server');
                        self.sendICECandidate(event.candidate);
                    }
                };

                // Connection state monitoring
                this.pc.onconnectionstatechange = function() {
                    console.log('Connection state: ' + self.pc.connectionState);

                    if (self.pc.connectionState === 'connected') {
                        self.updateStatus('WebRTC Connected - Low latency', 'connected');
                    } else if (self.pc.connectionState === 'failed') {
                        console.error('❌ WebRTC connection failed');
                        self.fallbackToWebSocket();
                    }
                };

                // Connect signaling WebSocket and wait for offer from server
                this.connectSignaling();

            } catch (e) {
                console.error('❌ Failed to create RTCPeerConnection:', e);
                this.fallbackToWebSocket();
            }
        };

        WebRTCReceiver.prototype.startLatencyMonitoring = function() {
            var self = this;

            // Monitor video frame timing via requestVideoFrameCallback
            if (self.video.requestVideoFrameCallback) {
                var measureFrame = function(now, metadata) {
                    self.frameCount++;

                    // Estimate latency using presentation timestamp
                    if (metadata && metadata.presentationTime) {
                        var latency = performance.now() - metadata.presentationTime;

                        if (latency > 0 && latency < 5000) {
                            self.latencySamples.push(latency);
                            if (self.latencySamples.length > self.maxLatencySamples) {
                                self.latencySamples.shift();
                            }
                        }
                    }

                    // Update status with average latency
                    if (self.latencySamples.length > 0) {
                        var sum = 0;
                        for (var i = 0; i < self.latencySamples.length; i++) {
                            sum += self.latencySamples[i];
                        }
                        var avgLatency = Math.round(sum / self.latencySamples.length);
                        self.updateStatus('WebRTC - ' + self.frameCount + ' frames | Latency: ~' + avgLatency + 'ms', 'connected');
                    }

                    self.video.requestVideoFrameCallback(measureFrame);
                };

                self.video.requestVideoFrameCallback(measureFrame);
            } else {
                console.warn('⚠️ requestVideoFrameCallback not supported - latency monitoring disabled');
            }
        };

        WebRTCReceiver.prototype.connectSignaling = function() {
            var self = this;
            var protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
            var wsUrl = protocol + '://' + window.location.host + '/stream';

            console.log('📡 Connecting signaling WebSocket: ' + wsUrl);

            try {
                this.ws = new WebSocket(wsUrl);

                this.ws.onopen = function() {
                    console.log('✅ Signaling WebSocket connected');
                    self.updateStatus('Waiting for WebRTC offer...', 'connecting');
                    // Wait for offer from Android server
                };

                this.ws.onmessage = function(event) {
                    try {
                        var message = JSON.parse(event.data);

                        if (message.type === 'SDP') {
                            console.log('📨 Received SDP: ' + message.sdpType);

                            // Filter VP8 from SDP to force H.264 for better TV compatibility
                            var filteredSdpString = filterVp8FromSdp(message.sdp);
                            var sdp = new RTCSessionDescription({
                                type: message.sdpType,
                                sdp: filteredSdpString
                            });

                            if (message.sdpType === 'offer') {
                                // Received offer from Android - create answer
                                console.log('📥 Received offer from server - creating answer (VP8 filtered)');
                                self.updateStatus('Negotiating WebRTC (H.264)...', 'connecting');
                                self.pc.setRemoteDescription(sdp).then(function() {
                                    return self.createAnswer();
                                }).catch(function(error) {
                                    console.error('❌ Failed to process offer:', error);
                                    self.fallbackToWebSocket();
                                });
                            } else {
                                // Other SDP types (shouldn't happen in this flow)
                                self.pc.setRemoteDescription(sdp);
                            }
                        } else if (message.type === 'ICE') {
                            console.log('📨 Received ICE candidate from server');
                            var candidate = new RTCIceCandidate({
                                sdpMid: message.sdpMid,
                                sdpMLineIndex: message.sdpMLineIndex,
                                candidate: message.candidate
                            });
                            self.pc.addIceCandidate(candidate);
                        } else if (message.type === 'VIDEO_URL') {
                            // NEW: Handle YouTube video loading
                            console.log('📺 Received YouTube video: ' + message.videoId);
                            self.loadYouTubeVideo(message.videoId, message.currentTime || 0);
                        } else if (message.type === 'VIDEO_CONTROL') {
                            // NEW: Handle video control commands
                            console.log('🎮 Received video control: ' + message.command);
                            self.handleVideoControl(message.command, message.value);
                        }
                    } catch (e) {
                        console.log('Non-JSON message, ignoring');
                    }
                };

                this.ws.onerror = function(error) {
                    console.error('❌ Signaling WebSocket error:', error);
                    self.fallbackToWebSocket();
                };

                this.ws.onclose = function() {
                    console.log('🔌 Signaling WebSocket closed');
                };

            } catch (e) {
                console.error('❌ Failed to create signaling WebSocket:', e);
                this.fallbackToWebSocket();
            }
        };

        WebRTCReceiver.prototype.createAnswer = function() {
            var self = this;

            return this.pc.createAnswer().then(function(answer) {
                console.log('📤 Created answer');
                return self.pc.setLocalDescription(answer);
            }).then(function() {
                console.log('📤 Sending answer to server');
                return self.sendAnswer(self.pc.localDescription);
            }).catch(function(error) {
                console.error('❌ Error creating answer:', error);
                self.fallbackToWebSocket();
            });
        };

        WebRTCReceiver.prototype.sendAnswer = function(answer) {
            return fetch('/webrtc-answer', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    type: answer.type,
                    sdp: answer.sdp
                })
            }).then(function(response) {
                return response.json();
            }).then(function(data) {
                console.log('✅ Answer sent successfully');
            }).catch(function(error) {
                console.error('❌ Failed to send answer:', error);
            });
        };

        WebRTCReceiver.prototype.sendICECandidate = function(candidate) {
            fetch('/webrtc-ice', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sdpMid: candidate.sdpMid,
                    sdpMLineIndex: candidate.sdpMLineIndex,
                    candidate: candidate.candidate
                })
            });
        };

        WebRTCReceiver.prototype.loadYouTubeVideo = function(videoId, startTime) {
            console.log('🎥 Loading YouTube video:', videoId, 'at', startTime, 's');

            var welcomeScreen = document.getElementById('welcome-screen');
            var youtubeContainer = document.getElementById('youtube-container');
            var youtubePlayer = document.getElementById('youtube-player');

            // Hide welcome screen and show YouTube player
            welcomeScreen.classList.add('hidden');
            youtubeContainer.classList.remove('hidden');

            // Create YouTube embed URL with parameters for TV compatibility
            var embedUrl = 'https://www.youtube-nocookie.com/embed/' + videoId +
                          '?autoplay=1&controls=1&rel=0&showinfo=0&iv_load_policy=3&modestbranding=1' +
                          '&playsinline=1&start=' + Math.floor(startTime) +
                          '&enablejsapi=1&origin=' + encodeURIComponent(window.location.origin);

            youtubePlayer.src = embedUrl;

            console.log('✅ YouTube video loaded successfully');
        };

        WebRTCReceiver.prototype.handleVideoControl = function(command, value) {
            console.log('🎮 Video control:', command, value);

            var welcomeScreen = document.getElementById('welcome-screen');
            var youtubeContainer = document.getElementById('youtube-container');
            var youtubePlayer = document.getElementById('youtube-player');

            switch (command) {
                case 'play':
                    console.log('▶️ Play command received');
                    break;
                case 'pause':
                    console.log('⏸️ Pause command received');
                    break;
                case 'seek':
                    console.log('⏩ Seek to:', value, 's');
                    break;
                case 'stop':
                    console.log('⏹️ Stop command received - returning to welcome screen');
                    // Hide YouTube player and show welcome screen
                    youtubeContainer.classList.add('hidden');
                    welcomeScreen.classList.remove('hidden');
                    // Clear the iframe src to stop playback
                    youtubePlayer.src = '';
                    break;
                default:
                    console.log('Unknown video control:', command);
            }
        };

        WebRTCReceiver.prototype.updateStatus = function(text, className) {
            this.status.textContent = text;
            this.status.className = 'status-' + className;
        };

        WebRTCReceiver.prototype.fallbackToWebSocket = function() {
            console.log('⚠️ Falling back to WebSocket streaming...');
            this.updateStatus('Using WebSocket fallback', 'connecting');

            // Cleanup WebRTC
            if (this.pc) {
                this.pc.close();
                this.pc = null;
            }
            if (this.ws) {
                this.ws.close();
                this.ws = null;
            }

            // Reload with fallback parameter
            window.location.href = '/?fallback=websocket';
        };

        // WebSocket Receiver (fallback)
        function StreamReceiver() {
            this.img = document.getElementById('stream');
            this.status = document.getElementById('status');
            this.ws = null;
            this.reconnectAttempts = 0;
            this.maxReconnectAttempts = 25;  // Sufficient attempts for all devices
            this.reconnectDelay = 3000;      // Universal delay for stability
            this.lastFrameTime = Date.now();
            this.frameCount = 0;

            // Latency measurement
            this.lastTimestamp = 0;
            this.latencySamples = [];
            this.maxLatencySamples = 10;  // Average over last 10 frames

            // Mirror effect for front camera (default)
            // TODO: Make dynamic via WebSocket message when camera switches
            this.isFrontCamera = true;
            if (this.isFrontCamera) {
                this.img.classList.add('mirror');
                console.log('📷 Front camera: Mirror effect enabled');
            }

            console.log('🚀 WebSocket client initializing...');
            this.connect();
        }

        StreamReceiver.prototype.connect = function() {
            var self = this;
            var protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
            var wsUrl = protocol + "://" + window.location.host + "/stream";

            console.log('📡 Connecting to: ' + wsUrl);
            console.log('🔧 Universal WebSocket client mode');
            this.updateStatus('Connecting...', 'connecting');

            try {
                this.ws = new WebSocket(wsUrl);

                // Set binary type only if supported (some older TV browsers may not support it)
                if (this.ws.binaryType !== undefined) {
                    this.ws.binaryType = 'arraybuffer';
                    console.log('✅ Binary type set to arraybuffer');
                } else {
                    console.log('⚠️ Binary type not supported, using default');
                }

                this.ws.onopen = function() {
                    console.log('✅ WebSocket connected successfully!');
                    self.updateStatus('Connected - Streaming', 'connected');
                    self.reconnectAttempts = 0;
                    self.frameCount = 0;
                    self.lastFrameTime = Date.now();
                };

                this.ws.onmessage = function(event) {
                    self.handleFrame(event.data);
                };

                this.ws.onclose = function(event) {
                    console.log('🔌 WebSocket closed: ' + event.code + ' - ' + event.reason);

                    // Check if streaming was intentionally stopped by server
                    if (event.reason === 'STREAMING_STOPPED') {
                        console.log('⏹️ Streaming stopped by server - not reconnecting');
                        self.updateStatus('Streaming Stopped', 'disconnected');
                        return;
                    }

                    // For other disconnections, attempt to reconnect
                    self.updateStatus('Disconnected', 'disconnected');
                    self.scheduleReconnect();
                };

                this.ws.onerror = function(error) {
                    console.error('❌ WebSocket error:', error);
                    self.updateStatus('Connection Error', 'disconnected');
                };
            } catch (error) {
                console.error('❌ Failed to create WebSocket:', error);
                this.updateStatus('Connection Failed', 'disconnected');
                this.scheduleReconnect();
            }
        };

        StreamReceiver.prototype.handleFrame = function(data) {
            var self = this;
            try {
                // Check if data is a string (JSON message) or binary (JPEG frame)
                if (typeof data === 'string') {
                    self.handleMessage(data);
                    return;
                }

                this.frameCount++;
                var now = Date.now();
                this.lastFrameTime = now;

                // Calculate latency if we have timestamp from server
                if (this.lastTimestamp > 0) {
                    var latency = now - this.lastTimestamp;

                    // FIX #5: Validation - reject impossible values
                    if (latency > 0 && latency < 5000) {  // Must be between 0-5000ms
                        this.latencySamples.push(latency);

                        // Keep only last N samples for rolling average
                        if (this.latencySamples.length > this.maxLatencySamples) {
                            this.latencySamples.shift();
                        }
                    } else {
                        console.log('⚠️ Rejected invalid latency: ' + latency + 'ms (timestamp=' + this.lastTimestamp + ')');
                    }

                    // FIX #5: Reset to prevent stale timestamp reuse!
                    this.lastTimestamp = 0;
                }

                // OPTIMIZATION: Direct img.src rendering (saves 5-10ms vs canvas.drawImage)
                var blob = new Blob([data], { type: 'image/jpeg' });
                var url = URL.createObjectURL(blob);

                // Clean up old URL before setting new one
                if (this.img.src && this.img.src.startsWith('blob:')) {
                    URL.revokeObjectURL(this.img.src);
                }

                // Direct rendering - browser handles decoding and display (GPU accelerated!)
                this.img.src = url;

                // Update status with frame count and latency
                if (this.latencySamples.length > 0) {
                    var sum = 0;
                    for (var i = 0; i < this.latencySamples.length; i++) {
                        sum += this.latencySamples[i];
                    }
                    var avgLatency = Math.round(sum / this.latencySamples.length);
                    self.updateStatus('Streaming - ' + self.frameCount + ' frames | Latency: ' + avgLatency + 'ms', 'connected');
                } else {
                    self.updateStatus('Streaming - ' + self.frameCount + ' frames', 'connected');
                }
            } catch (error) {
                console.error('❌ Failed to process frame:', error);
            }
        };

        StreamReceiver.prototype.handleMessage = function(messageString) {
            try {
                var message = JSON.parse(messageString);
                console.log('📨 Received message:', message);

                switch (message.type) {
                    case 'TIMESTAMP':
                        // Store timestamp for latency calculation
                        this.lastTimestamp = message.timestamp;
                        console.log('📊 Timestamp received: ' + message.timestamp + ' (now: ' + Date.now() + ')');
                        break;
                    case 'VIDEO_URL':
                        this.loadYouTubeVideo(message.videoId, message.currentTime || 0);
                        break;
                    case 'VIDEO_CONTROL':
                        this.handleVideoControl(message.command, message.value);
                        break;
                    default:
                        console.log('Unknown message type:', message.type);
                }
            } catch (error) {
                console.error('❌ Failed to parse message:', error);
            }
        };

        StreamReceiver.prototype.loadYouTubeVideo = function(videoId, startTime) {
            console.log('🎥 Loading YouTube video:', videoId, 'at', startTime, 's');

            var welcomeScreen = document.getElementById('welcome-screen');
            var youtubeContainer = document.getElementById('youtube-container');
            var youtubePlayer = document.getElementById('youtube-player');

            // Hide welcome screen and show YouTube player
            welcomeScreen.classList.add('hidden');
            youtubeContainer.classList.remove('hidden');

            // Create YouTube embed URL with parameters for TV compatibility
            var embedUrl = 'https://www.youtube-nocookie.com/embed/' + videoId +
                          '?autoplay=1&controls=1&rel=0&showinfo=0&iv_load_policy=3&modestbranding=1' +
                          '&playsinline=1&start=' + Math.floor(startTime) +
                          '&enablejsapi=1&origin=' + encodeURIComponent(window.location.origin);

            youtubePlayer.src = embedUrl;

            this.updateStatus('Playing: YouTube + Camera', 'connected');
            console.log('✅ YouTube video loaded successfully');
        };

        StreamReceiver.prototype.handleVideoControl = function(command, value) {
            console.log('🎮 Video control:', command, value);

            var welcomeScreen = document.getElementById('welcome-screen');
            var youtubeContainer = document.getElementById('youtube-container');
            var youtubePlayer = document.getElementById('youtube-player');

            switch (command) {
                case 'play':
                    console.log('▶️ Play command received');
                    break;
                case 'pause':
                    console.log('⏸️ Pause command received');
                    break;
                case 'seek':
                    console.log('⏩ Seek to:', value, 's');
                    break;
                case 'stop':
                    console.log('⏹️ Stop command received - returning to welcome screen');
                    // Hide YouTube player and show welcome screen
                    youtubeContainer.classList.add('hidden');
                    welcomeScreen.classList.remove('hidden');
                    // Clear the iframe src to stop playback
                    youtubePlayer.src = '';
                    this.updateStatus('Camera Stream Only', 'connected');
                    break;
                default:
                    console.log('Unknown video control:', command);
            }
        };

        StreamReceiver.prototype.updateStatus = function(message, type) {
            this.status.textContent = message;
            this.status.className = "status-" + type;
        };

        StreamReceiver.prototype.scheduleReconnect = function() {
            var self = this;
            if (this.reconnectAttempts < this.maxReconnectAttempts) {
                this.reconnectAttempts++;
                var delay = this.reconnectDelay * this.reconnectAttempts;

                // Limit maximum delay for TV compatibility
                if (delay > 15000) delay = 15000;

                console.log("🔄 Reconnecting in " + delay + "ms (attempt " + this.reconnectAttempts + "/" + this.maxReconnectAttempts + ")");
                this.updateStatus("Reconnecting in " + Math.ceil(delay/1000) + "s...", 'connecting');

                setTimeout(function() {
                    self.connect();
                }, delay);
            } else {
                this.updateStatus('Connection Failed - Check Network', 'disconnected');
                console.log('❌ Max reconnection attempts reached');
            }
        };

        StreamReceiver.prototype.disconnect = function() {
            if (this.ws) {
                this.ws.close();
                this.ws = null;
            }
        };

        // Global receiver instance
        var streamReceiver = null;

        // Start the stream receiver when page loads
        function initializeReceiver() {
            console.log('🚀 Initializing Stream Receiver...');

            // Check if fallback parameter is set
            var urlParams = new URLSearchParams(window.location.search);
            var useFallback = urlParams.get('fallback') === 'websocket';

            // Detect WebRTC support
            var supportsWebRTC = !!(
                window.RTCPeerConnection ||
                window.webkitRTCPeerConnection ||
                window.mozRTCPeerConnection
            );

            console.log('WebRTC supported: ' + supportsWebRTC);
            console.log('Fallback parameter: ' + useFallback);

            // Choose receiver
            if (supportsWebRTC && !useFallback) {
                console.log('✅ Using WebRTC receiver (low latency)');
                streamReceiver = new WebRTCReceiver();
            } else {
                console.log('⚠️ Using WebSocket receiver (fallback)');
                streamReceiver = new StreamReceiver();
            }
        }

        // Universal cross-browser event listeners
        if (document.addEventListener) {
            document.addEventListener('DOMContentLoaded', initializeReceiver);
        } else if (document.attachEvent) {
            document.attachEvent('onreadystatechange', function() {
                if (document.readyState === 'complete') {
                    initializeReceiver();
                }
            });
        }

        // Handle page visibility changes (if supported)
        if (typeof document.addEventListener === 'function') {
            document.addEventListener('visibilitychange', function() {
                if (document.hidden) {
                    console.log('📱 Page hidden - maintaining connection');
                } else {
                    console.log('📱 Page visible - checking connection');
                    if (streamReceiver && (!streamReceiver.ws || streamReceiver.ws.readyState !== WebSocket.OPEN)) {
                        console.log('🔄 Reconnecting due to page visibility change');
                        streamReceiver.connect();
                    }
                }
            });
        }

        // Handle window focus/blur (if supported)
        if (typeof window.addEventListener === 'function') {
            window.addEventListener('focus', function() {
                console.log('🎯 Window focused');
            });

            window.addEventListener('blur', function() {
                console.log('😴 Window blurred');
            });
        }

        // Cleanup on page unload
        if (typeof window.addEventListener === 'function') {
            window.addEventListener('beforeunload', function() {
                if (streamReceiver) {
                    streamReceiver.disconnect();
                }
            });
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    // WebRTC Signaling Methods

    /**
     * Handle WebRTC offer from TV client
     */
    private fun handleWebRTCOffer(session: IHTTPSession): Response {
        return try {
            // Parse POST body
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"Missing POST data"}"""
            )

            // Parse JSON
            val json = JSONObject(postData)
            val type = json.getString("type")
            val sdp = json.getString("sdp")

            Log.d(TAG, "Received WebRTC offer - type: $type")

            // Create SessionDescription and notify callback
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type),
                sdp
            )
            callback.onWebRTCOffer(sessionDescription)

            // Return success
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"offer received"}"""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle WebRTC offer", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"${e.message}"}"""
            )
        }
    }

    /**
     * Handle WebRTC answer from TV client
     */
    private fun handleWebRTCAnswer(session: IHTTPSession): Response {
        return try {
            // Parse POST body
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"Missing POST data"}"""
            )

            // Parse JSON
            val json = JSONObject(postData)
            val type = json.getString("type")
            val sdp = json.getString("sdp")

            Log.d(TAG, "Received WebRTC answer - type: $type")

            // Create SessionDescription and notify callback
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type),
                sdp
            )
            callback.onWebRTCAnswer(sessionDescription)

            // Return success
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"answer received"}"""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle WebRTC answer", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"${e.message}"}"""
            )
        }
    }

    /**
     * Handle ICE candidate from TV client
     */
    private fun handleWebRTCIceCandidate(session: IHTTPSession): Response {
        return try {
            // Parse POST body
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"Missing POST data"}"""
            )

            // Parse JSON
            val json = JSONObject(postData)
            val sdpMid = json.getString("sdpMid")
            val sdpMLineIndex = json.getInt("sdpMLineIndex")
            val candidate = json.getString("candidate")

            Log.d(TAG, "Received ICE candidate: $candidate")

            // Create IceCandidate and notify callback
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            callback.onWebRTCIceCandidate(iceCandidate)

            // Return success
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"ice candidate received"}"""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle ICE candidate", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"${e.message}"}"""
            )
        }
    }

    /**
     * Send SDP (offer/answer) to TV client via WebSocket
     */
    fun sendSdpToClient(sdp: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "SDP")
            put("sdpType", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }

        currentWebSocket?.send(json.toString())
        Log.d(TAG, "Sent SDP to client: ${sdp.type.canonicalForm()}")
    }

    /**
     * Send ICE candidate to TV client via WebSocket
     */
    fun sendICECandidateToClient(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("type", "ICE")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }

        currentWebSocket?.send(json.toString())
        Log.d(TAG, "Sent ICE candidate to client")
    }

    internal class StreamingWebSocket(
        handshake: IHTTPSession,
        private val server: StreamingServer
    ) : WebSocket(handshake) {

        override fun onOpen() {
            Log.i(TAG, "✅ WebSocket opened successfully!")
            Log.d(TAG, "WebSocket details: ${this.handshakeRequest?.headers}")
            server.handleClientConnected(this)
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode,
            reason: String,
            initiatedByRemote: Boolean
        ) {
            Log.i(TAG, "🔌 WebSocket closed:")
            Log.i(TAG, "  Code: $code")
            Log.i(TAG, "  Reason: $reason")
            Log.i(TAG, "  Initiated by remote: $initiatedByRemote")
            server.handleClientDisconnected()
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            // We don't expect messages from client in this implementation
            Log.d(TAG, "Received message from client: ${message.textPayload}")
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) {
            Log.d(TAG, "Received pong - connection alive")
        }

        override fun onException(exception: IOException) {
            Log.e(TAG, "WebSocket exception", exception)

            // Enhanced error handling for connection stability
            when {
                exception is java.net.SocketTimeoutException -> {
                    Log.w(TAG, "WebSocket timeout - attempting graceful disconnect")
                    // Don't immediately disconnect on timeout, let ping/pong handle it
                    try {
                        close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "Timeout", false)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to close gracefully after timeout", e)
                    }
                }
                exception.message?.contains("Connection reset") == true -> {
                    Log.w(TAG, "WebSocket connection reset by client - normal during page refresh")
                }
                exception.message?.contains("Broken pipe") == true -> {
                    Log.w(TAG, "WebSocket broken pipe - client disconnected normally")
                }
                exception.message?.contains("Software caused connection abort") == true -> {
                    Log.w(TAG, "WebSocket software connection abort - client disconnected or network issue")
                    // This is often normal when user closes browser or navigates away
                }
                exception.message?.contains("Connection refused") == true -> {
                    Log.w(TAG, "WebSocket connection refused - client may be busy")
                }
                else -> {
                    Log.e(TAG, "Unexpected WebSocket exception: ${exception.message}")
                }
            }

            // Always handle disconnection, but with improved logging
            server.handleClientDisconnected()
        }
    }

    internal class SSEClient(
        private val writer: java.io.OutputStreamWriter,
        private val outputStream: java.io.OutputStream
    ) {
        fun sendEvent(event: String, data: String) {
            try {
                writer.write("event: $event\n")
                writer.write("data: $data\n\n")
                writer.flush()
            } catch (e: Exception) {
                throw e
            }
        }

        fun sendFrame(base64Data: String) {
            try {
                val frameData = """{"type":"frame","data":"$base64Data"}"""
                writer.write("data: $frameData\n\n")
                writer.flush()
            } catch (e: Exception) {
                throw e
            }
        }
    }
}