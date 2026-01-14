package com.fitnessmirror.webrtc.network

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Broadcasts presence on the local network for FitnessMirrorTV discovery.
 * Sends UDP broadcast packets every 2 seconds on port 8081.
 */
class DiscoveryBroadcaster(private val serverPort: Int = 8080) {

    companion object {
        private const val TAG = "DiscoveryBroadcaster"
        private const val BROADCAST_PORT = 8081
        private const val BROADCAST_INTERVAL_MS = 2000L
        private const val DISCOVERY_TYPE = "FITNESS_MIRROR_DISCOVERY"
    }

    private var isRunning = false
    private var broadcastSocket: DatagramSocket? = null
    private var broadcastThread: Thread? = null

    /**
     * Start broadcasting presence
     * @param deviceName Name to identify this device (default: device model)
     */
    fun start(deviceName: String = Build.MODEL) {
        if (isRunning) {
            Log.w(TAG, "Already broadcasting")
            return
        }

        isRunning = true
        broadcastThread = thread(name = "DiscoveryBroadcaster") {
            try {
                broadcastSocket = DatagramSocket().apply {
                    broadcast = true
                    reuseAddress = true
                }

                val localIp = NetworkUtils.getLocalIpAddress()
                if (localIp == null) {
                    Log.e(TAG, "Cannot get local IP address")
                    return@thread
                }

                Log.i(TAG, "Starting broadcast from $localIp:$serverPort as '$deviceName'")

                while (isRunning) {
                    try {
                        val message = createBroadcastMessage(localIp, deviceName)
                        val data = message.toByteArray()

                        // Broadcast to 255.255.255.255
                        val packet = DatagramPacket(
                            data,
                            data.size,
                            InetAddress.getByName("255.255.255.255"),
                            BROADCAST_PORT
                        )

                        broadcastSocket?.send(packet)
                        Log.d(TAG, "Broadcast sent: $message")

                        Thread.sleep(BROADCAST_INTERVAL_MS)

                    } catch (e: InterruptedException) {
                        Log.d(TAG, "Broadcast interrupted")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Broadcast error: ${e.message}")
                        Thread.sleep(BROADCAST_INTERVAL_MS)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start broadcast: ${e.message}")
            } finally {
                broadcastSocket?.close()
                broadcastSocket = null
                Log.i(TAG, "Broadcast stopped")
            }
        }
    }

    private fun createBroadcastMessage(ip: String, deviceName: String): String {
        return JSONObject().apply {
            put("type", DISCOVERY_TYPE)
            put("ip", ip)
            put("port", serverPort)
            put("name", deviceName)
        }.toString()
    }

    /**
     * Stop broadcasting
     */
    fun stop() {
        Log.i(TAG, "Stopping broadcast")
        isRunning = false
        broadcastThread?.interrupt()
        broadcastSocket?.close()
        broadcastThread = null
    }

    /**
     * Check if currently broadcasting
     */
    fun isActive(): Boolean = isRunning
}
