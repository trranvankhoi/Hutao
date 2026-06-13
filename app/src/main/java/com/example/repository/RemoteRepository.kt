package com.example.repository

import com.example.model.DiscoveredServer
import com.example.model.NetworkCommand
import com.example.network.ControlClient
import com.example.network.LanDiscovery
import com.example.network.VideoReceiver
import kotlinx.coroutines.flow.SharedFlow

class RemoteRepository(
    private val controlClient: ControlClient,
    private val videoReceiver: VideoReceiver,
    private val lanDiscovery: LanDiscovery
) {
    val streamFrames = videoReceiver.videoFrames
    val controlResponses = controlClient.responses
    val discoveredServers = lanDiscovery.discoveredServers

    var isConnected: Boolean
        get() = controlClient.isConnected
        set(_) {}

    suspend fun discoverServers() {
        lanDiscovery.startSearch()
    }

    suspend fun stopDiscovery() {
        lanDiscovery.stopSearch()
    }

    suspend fun connect(ip: String, port: Int, passwordPlain: String): Result<String> {
        return controlClient.connect(ip, port, passwordPlain)
    }

    fun disconnect() {
        controlClient.close()
        videoReceiver.stop()
    }

    fun sendCommand(token: String, command: NetworkCommand) {
        controlClient.sendCommand(token, command)
    }

    fun startVideoStream(udpPort: Int) {
        videoReceiver.start(udpPort)
    }

    fun stopVideoStream() {
        videoReceiver.stop()
    }
}
