package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DiscoveredServer
import com.example.ui.theme.*
import com.example.viewmodel.AppUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    state: AppUiState,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onScanRequested: () -> Unit,
    onServerSelected: (DiscoveredServer) -> Unit,
    onConnectRequested: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Remote Pairing Console",
                            color = ImmersiveTextMain,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Unified Client Gateway",
                            color = ImmersiveTextSub,
                            fontSize = 11.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ImmersiveBg,
                    titleContentColor = ImmersiveTextMain
                ),
                actions = {
                    IconButton(onClick = onScanRequested) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Scan PC hosts",
                            tint = ImmersiveAccent
                        )
                    }
                },
                modifier = Modifier.border(width = 0.5.dp, color = ImmersiveBorder)
            )
        },
        containerColor = ImmersiveBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Connection Status HUD Block ---
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = ImmersiveSurface
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ImmersiveBorder, RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (state.isConnected) ImmersiveActiveGreen else ImmersiveAlertRed,
                                shape = RoundedCornerShape(50)
                            )
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "STATUS: ${state.connectionStatus.uppercase()}",
                            fontWeight = FontWeight.Bold,
                            color = ImmersiveTextMain,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (state.isConnected) "Telemetry synchronized successfully" else "Standby. Setup credentials or select a LAN host.",
                            fontSize = 11.sp,
                            color = ImmersiveTextSub
                        )
                    }
                }
            }

            // --- PC IP Address Input ---
            OutlinedTextField(
                value = state.ipAddress,
                onValueChange = onIpChanged,
                label = { Text("PC IP Address", color = ImmersiveTextSub) },
                leadingIcon = { Icon(Icons.Default.Computer, contentDescription = "IP", tint = ImmersiveAccent) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ImmersiveTextMain,
                    unfocusedTextColor = ImmersiveTextMain,
                    focusedBorderColor = ImmersiveAccent,
                    unfocusedBorderColor = ImmersiveBorder,
                    cursorColor = ImmersiveAccent,
                    focusedLabelColor = ImmersiveAccent,
                    unfocusedLabelColor = ImmersiveTextSub
                )
            )

            // --- Port & Password Inputs Row ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.port,
                    onValueChange = onPortChanged,
                    label = { Text("Port", color = ImmersiveTextSub) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ImmersiveTextMain,
                        unfocusedTextColor = ImmersiveTextMain,
                        focusedBorderColor = ImmersiveAccent,
                        unfocusedBorderColor = ImmersiveBorder,
                        cursorColor = ImmersiveAccent,
                        focusedLabelColor = ImmersiveAccent,
                        unfocusedLabelColor = ImmersiveTextSub
                    )
                )

                OutlinedTextField(
                    value = state.passwordPlain,
                    onValueChange = onPasswordChanged,
                    label = { Text("Password", color = ImmersiveTextSub) },
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "Pass icon", tint = ImmersiveAccent) },
                    modifier = Modifier.weight(1.8f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ImmersiveTextMain,
                        unfocusedTextColor = ImmersiveTextMain,
                        focusedBorderColor = ImmersiveAccent,
                        unfocusedBorderColor = ImmersiveBorder,
                        cursorColor = ImmersiveAccent,
                        focusedLabelColor = ImmersiveAccent,
                        unfocusedLabelColor = ImmersiveTextSub
                    )
                )
            }

            // --- Action Connection Trigger Button ---
            Button(
                onClick = onConnectRequested,
                enabled = !state.isConnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ImmersiveAccent,
                    contentColor = ImmersiveButtonDeep,
                    disabledContainerColor = ImmersiveSurface
                )
            ) {
                if (state.isConnecting) {
                    CircularProgressIndicator(color = ImmersiveButtonDeep, modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        text = "PAIR AND CONNECT",
                        fontWeight = FontWeight.Bold,
                        color = ImmersiveButtonDeep,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // --- Error Display ---
            AnimatedVisibility(visible = state.errorMessage != null) {
                Text(
                    text = state.errorMessage ?: "",
                    color = ImmersiveAlertLight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // --- LAN Auto-Discovered Section Header ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto-Discovered LAN Hosts",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ImmersiveTextMain,
                    letterSpacing = 0.5.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ImmersiveSurface)
                        .clickable { onScanRequested() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "SCAN",
                        fontSize = 10.sp,
                        color = ImmersiveAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // --- Discovered Servers List ---
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.availableServers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp)
                                .border(1.dp, ImmersiveBorder, RoundedCornerShape(12.dp))
                                .background(ImmersiveSurface.copy(alpha = 0.3f))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = "No Server found",
                                    tint = ImmersiveTextSub,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No PC server active yet on local sub-network.",
                                    color = ImmersiveTextSub,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else {
                    items(state.availableServers) { server ->
                        Card(
                            onClick = { onServerSelected(server) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, ImmersiveBorder, RoundedCornerShape(10.dp)),
                            colors = CardDefaults.cardColors(
                                containerColor = ImmersiveSurface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CastConnected,
                                        contentDescription = "Server Hook", 
                                        tint = ImmersiveAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text(
                                            text = server.hostName,
                                            fontWeight = FontWeight.Bold,
                                            color = ImmersiveTextMain,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = server.ipAddress,
                                            fontSize = 11.sp,
                                            color = ImmersiveTextSub,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Pick server target",
                                    tint = ImmersiveAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
