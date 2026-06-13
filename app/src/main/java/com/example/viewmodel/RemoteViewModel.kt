package com.example.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.DiscoveredServer
import com.example.model.NetworkCommand
import com.example.repository.RemoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class AppUiState(
    val ipAddress: String = "192.168.1.100",  // Preset default LAN IP
    val port: String = "5000",
    val passwordPlain: String = "Admin123",
    
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val connectionStatus: String = "Disconnected",
    val errorMessage: String? = null,
    
    // Performance stats
    val pingMs: Long = 0,
    val currentFps: Int = 0,
    val currentQuality: Int = 60,
    val currentScaled: Float = 1.0f,
    
    // Remote parameters state
    val availableServers: List<DiscoveredServer> = emptyList(),
    val activeFrame: Bitmap? = null
)

class RemoteViewModel(
    private val repository: RemoteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var token: String = ""
    private var pingJob: Job? = null
    private var streamingUdpPort = 5001
    
    // High-performance metrics variables
    private var frameCount = 0
    private var lastFpsTimestamp = 0L

    init {
        // Collect stream frames flow securely
        viewModelScope.launch {
            repository.streamFrames.collect { bitmap ->
                incrementFpsMetrics()
                _uiState.update { it.copy(activeFrame = bitmap) }
            }
        }

        // Collect Control Connection JSON stream responses
        viewModelScope.launch {
            repository.controlResponses.collect { rawJson ->
                try {
                    val obj = JSONObject(rawJson)
                    // Server answers for pings or other status logs are safely handled
                    val status = obj.optString("status")
                    if (status == "success" && obj.has("info")) {
                        // Successfully logged command actions
                    }
                } catch (e: Exception) {
                    Log.e("ViewModel", "JSON process failure: ${e.message}")
                }
            }
        }

        // Collect newly swept servers from UDP scanner
        viewModelScope.launch {
            repository.discoveredServers.collect { server ->
                _uiState.update { state ->
                    val currentList = state.availableServers.toMutableList()
                    if (currentList.none { it.ipAddress == server.ipAddress }) {
                        currentList.add(server)
                    }
                    state.copy(availableServers = currentList)
                }
            }
        }
    }

    fun onAddressChanged(ip: String) {
        _uiState.update { it.copy(ipAddress = ip) }
    }

    fun onPortChanged(port: String) {
        _uiState.update { it.copy(port = port) }
    }

    fun onPasswordChanged(pass: String) {
        _uiState.update { it.copy(passwordPlain = pass) }
    }

    fun scanServers() {
        viewModelScope.launch {
            _uiState.update { it.copy(availableServers = emptyList()) }
            repository.discoverServers()
        }
    }

    fun selectDiscoveredServer(server: DiscoveredServer) {
        _uiState.update {
            it.copy(
                ipAddress = server.ipAddress,
                port = server.tcpPort.toString()
            )
        }
    }

    fun connect() {
        val state = _uiState.value
        val portInt = state.port.toIntOrNull() ?: 5000
        
        _uiState.update { 
            it.copy(
                isConnecting = true, 
                errorMessage = null, 
                connectionStatus = "Connecting..."
            ) 
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.connect(state.ipAddress, portInt, state.passwordPlain)
            
            launch(Dispatchers.Main) {
                result.fold(
                    onSuccess = { sessionToken ->
                        token = sessionToken
                        _uiState.update {
                            it.copy(
                                isConnecting = false,
                                isConnected = true,
                                connectionStatus = "Connected"
                            )
                        }
                        
                        // Register UDP video channel
                        repository.startVideoStream(streamingUdpPort)
                        repository.sendCommand(token, NetworkCommand.RegisterVideoStream(streamingUdpPort))
                        
                        // Start active ping tracking thread task
                        startPingTracker()
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isConnecting = false,
                                isConnected = false,
                                connectionStatus = "Disconnected",
                                errorMessage = error.message ?: "Connection Failure"
                            )
                        }
                    }
                )
            }
        }
    }

    fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        repository.disconnect()
        _uiState.update {
            it.copy(
                isConnected = false,
                connectionStatus = "Disconnected",
                activeFrame = null,
                pingMs = 0,
                currentFps = 0
            )
        }
    }

    fun sendControlCommand(command: NetworkCommand) {
        if (_uiState.value.isConnected && token.isNotEmpty()) {
            viewModelScope.launch {
                repository.sendCommand(token, command)
            }
        }
    }

    fun toggleAdaptiveSettings(adaptationType: String) {
        val state = _uiState.value
        var q = state.currentQuality
        var f = state.currentFps
        var s = state.currentScaled
        
        when (adaptationType) {
            "low_network" -> {
                q = 40
                f = 20
                s = 0.5f
            }
            "medium_network" -> {
                q = 60
                f = 30
                s = 0.75f
            }
            "high_network" -> {
                q = 80
                f = 60
                s = 1.0f
            }
        }
        
        _uiState.update { it.copy(currentQuality = q) }
        sendControlCommand(NetworkCommand.UpdateStreamSettings(q, f, s))
    }

    private fun startPingTracker() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val start = System.currentTimeMillis()
                // Emit lightweight action verifying latency constraints
                // We reuse MouseMove state as ping beacon
                repository.sendCommand(token, NetworkCommand.MouseMove(-1.0f, -1.0f))
                delay(1000)
                val stop = System.currentTimeMillis()
                val delta = stop - start - 1000
                _uiState.update { it.copy(pingMs = if (delta >= 0) delta else 0) }
            }
        }
    }

    private fun incrementFpsMetrics() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTimestamp >= 1000) {
            val computedFps = frameCount
            frameCount = 0
            lastFpsTimestamp = now
            _uiState.update { it.copy(currentFps = computedFps) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
