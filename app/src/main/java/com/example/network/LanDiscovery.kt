package com.example.network

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.model.DiscoveredServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class LanDiscovery(private val context: Context) {
    private val _discoveredServers = MutableSharedFlow<DiscoveredServer>(extraBufferCapacity = 16)
    val discoveredServers: SharedFlow<DiscoveredServer> = _discoveredServers
    
    private var socket: DatagramSocket? = null
    private var isSearching = false

    /**
     * Broadcasts discovery pings, listening to incoming responses.
     */
    suspend fun startSearch() = withContext(Dispatchers.IO) {
        if (isSearching) return@withContext
        isSearching = true
        
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 1500 // Short timeouts supporting responsive scanning
            }
            
            val broadcastAddress = getBroadcastAddress() ?: InetAddress.getByName("255.255.255.255")
            val payload = "REMOTE_DISCOVER_REQUEST".toByteArray()
            val packet = DatagramPacket(payload, payload.size, broadcastAddress, 5002)
            
            // Pulse Broadcast 3 times to guarantee receipt
            for (i in 1..3) {
                socket?.send(packet)
                Thread.sleep(150)
            }
            
            // Standby and parse responding signals
            val rxBuffer = ByteArray(1024)
            val rxPacket = DatagramPacket(rxBuffer, rxBuffer.size)
            val startTime = System.currentTimeMillis()
            
            while (isSearching && (System.currentTimeMillis() - startTime < 3000)) {
                try {
                    socket?.receive(rxPacket)
                    val jsonStr = String(rxPacket.data, 0, rxPacket.length, Charsets.UTF_8)
                    val obj = JSONObject(jsonStr)
                    
                    if (obj.optString("type") == "REMOTE_DISCOVER_RESPONSE") {
                        val server = DiscoveredServer(
                            ipAddress = rxPacket.address.hostAddress ?: "",
                            hostName = obj.optString("server_name", "Windows PC"),
                            tcpPort = obj.optInt("tcp_port", 5000)
                        )
                        _discoveredServers.tryEmit(server)
                    }
                } catch (e: SocketTimeoutException) {
                    // Timeout is fine, check duration again
                } catch (e: Exception) {
                    Log.d("LanDiscovery", "Discovery receive exception: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("LanDiscovery", "LAN Discovery broadcase failing: ${e.message}")
        } finally {
            stopSearch()
        }
    }

    fun stopSearch() {
        isSearching = false
        socket?.close()
        socket = null
    }

    private fun getBroadcastAddress(): InetAddress? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val dhcp: DhcpInfo = wifiManager.dhcpInfo ?: return null
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val quads = ByteArray(4)
        for (k in 0..3) {
            quads[k] = ((broadcast shr (k * 8)) and 0xFF).toByte()
        }
        return InetAddress.getByAddress(quads)
    }
}
