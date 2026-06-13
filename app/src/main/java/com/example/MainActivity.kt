package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.network.ControlClient
import com.example.network.LanDiscovery
import com.example.network.VideoReceiver
import com.example.repository.RemoteRepository
import com.example.ui.screens.ConnectionScreen
import com.example.ui.screens.RemoteScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.RemoteViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: RemoteViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Manual DI implementation. Extremely robust, completely immune to build script classpath bugs.
        val client = ControlClient()
        val receiver = VideoReceiver()
        val discovery = LanDiscovery(applicationContext)
        val repo = RemoteRepository(client, receiver, discovery)
        
        viewModel = RemoteViewModel(repo)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()

                    if (state.isConnected) {
                        RemoteScreen(
                            state = state,
                            onSendCommand = { cmd -> viewModel.sendControlCommand(cmd) },
                            onSettingsChanged = { adaptation -> viewModel.toggleAdaptiveSettings(adaptation) },
                            onDisconnectRequested = { viewModel.disconnect() }
                        )
                    } else {
                        ConnectionScreen(
                            state = state,
                            onIpChanged = { ip -> viewModel.onAddressChanged(ip) },
                            onPortChanged = { port -> viewModel.onPortChanged(port) },
                            onPasswordChanged = { password -> viewModel.onPasswordChanged(password) },
                            onScanRequested = { viewModel.scanServers() },
                            onServerSelected = { server -> viewModel.selectDiscoveredServer(server) },
                            onConnectRequested = { viewModel.connect() }
                        )
                    }
                }
            }
        }
    }
}
