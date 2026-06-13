package com.example.ui.screens

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.NetworkCommand
import com.example.ui.theme.*
import com.example.viewmodel.AppUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    state: AppUiState,
    onSendCommand: (NetworkCommand) -> Unit,
    onSettingsChanged: (String) -> Unit,
    onDisconnectRequested: () -> Unit
) {
    // Zoom/Pinch state managers
    var scaleFactor by remember { mutableStateOf(1.0f) }
    var offsetX by remember { mutableStateOf(0.0f) }
    var offsetY by remember { mutableStateOf(0.0f) }

    // Screen dynamic bounds sizing
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Floating action/keyboard configurations
    var displayKeyboardOption by remember { mutableStateOf(false) }
    var displaySettingsOption by remember { mutableStateOf(false) }
    
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardFocusRequester = remember { FocusRequester() }
    
    // Virtual manual input text buffer
    var typedBufferText by remember { mutableStateOf("") }

    // Pulsing animation for connection dot
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotsize"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotalpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ImmersiveBg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // --- 1. Connection Status Bar Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ImmersiveBg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .drawBehind {
                    // bottom border/divider
                    drawLine(
                        color = ImmersiveBorder,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Pulse Green indicator light
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .scale(pulseScale)
                            .background(ImmersiveActiveGreen.copy(alpha = 0.25f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(ImmersiveActiveGreen, CircleShape)
                    )
                }

                Column {
                    Text(
                        text = "Connected: ${state.ipAddress.takeIf { it.isNotEmpty() } ?: "PC-SERVER-SYS"}",
                        color = ImmersiveTextMain,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "${state.ipAddress} • TCP:${state.port} • UDP:Auto",
                        color = ImmersiveTextSub,
                        fontSize = 10.sp
                    )
                }
            }

            // High priority live stats display
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${state.pingMs}ms",
                        color = ImmersiveAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "LATENCY",
                        color = ImmersiveTextSub,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${state.currentFps} FPS",
                        color = ImmersiveAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "STREAM",
                        color = ImmersiveTextSub,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // --- 2. Main content: Remote Viewport ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { viewSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (viewSize.width > 0 && viewSize.height > 0 && state.activeFrame != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scaleFactor = (scaleFactor * zoom).coerceIn(1.0f, 4.0f)
                                if (scaleFactor > 1.0f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        }
                        .pointerInput(viewSize) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val rx = offset.x / viewSize.width
                                    val ry = offset.y / viewSize.height
                                    onSendCommand(NetworkCommand.MouseMove(rx, ry))
                                    onSendCommand(NetworkCommand.MouseClick("left"))
                                },
                                onDoubleTap = { offset ->
                                    val rx = offset.x / viewSize.width
                                    val ry = offset.y / viewSize.height
                                    onSendCommand(NetworkCommand.MouseMove(rx, ry))
                                    onSendCommand(NetworkCommand.MouseClick("double"))
                                },
                                onLongPress = { offset ->
                                    val rx = offset.x / viewSize.width
                                    val ry = offset.y / viewSize.height
                                    onSendCommand(NetworkCommand.MouseMove(rx, ry))
                                    onSendCommand(NetworkCommand.MouseClick("right"))
                                }
                            )
                        }
                        .pointerInput(viewSize) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val rx = change.position.x / viewSize.width
                                val ry = change.position.y / viewSize.height
                                onSendCommand(NetworkCommand.MouseMove(rx, ry))
                            }
                        }
                ) {
                    Image(
                        bitmap = state.activeFrame.asImageBitmap(),
                        contentDescription = "Desktop remote render",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ImmersiveAccent)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Synchronizing desktop feed packets...",
                        color = ImmersiveTextSub,
                        fontSize = 13.sp
                    )
                }
            }

            // Floating Touchpad Indicators (Center overlay, decoration & guide)
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .align(Alignment.Center)
                    .drawBehind {
                        drawCircle(
                            color = ImmersiveAccent.copy(alpha = 0.25f),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                            )
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(ImmersiveAccent, CircleShape)
                )
            }
        }

        // Settings / Custom adaptation sheet overlays
        AnimatedVisibility(visible = displaySettingsOption) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Network stream settings",
                            color = ImmersiveTextMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        IconButton(onClick = { displaySettingsOption = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close settings", tint = ImmersiveTextSub)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSettingsChanged("low_network") },
                            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveBg),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Low Bandwidth", color = ImmersiveAccent, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { onSettingsChanged("high_network") },
                            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveBg),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("HD Mode", color = ImmersiveAccent, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Keyboard Panel capturing text entries
        AnimatedVisibility(visible = displayKeyboardOption) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Immersive Soft Keyboard Input", color = ImmersiveTextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { displayKeyboardOption = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Hide Keyboard", tint = ImmersiveTextSub)
                        }
                    }

                    // Hidden input receiver triggering text edits
                    Box(modifier = Modifier.size(1.dp)) {
                        TextField(
                            value = typedBufferText,
                            onValueChange = { chunk ->
                                if (chunk.length > typedBufferText.length) {
                                    val addedDiff = chunk.substring(typedBufferText.length)
                                    onSendCommand(NetworkCommand.KeyboardInput(text = addedDiff))
                                } else if (chunk.length < typedBufferText.length) {
                                    onSendCommand(NetworkCommand.KeyboardInput(keyCode = "backspace"))
                                }
                                typedBufferText = chunk
                            },
                            modifier = Modifier
                                .focusRequester(keyboardFocusRequester)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_ENTER -> {
                                                onSendCommand(NetworkCommand.KeyboardInput(keyCode = "enter"))
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DEL -> {
                                                onSendCommand(NetworkCommand.KeyboardInput(keyCode = "backspace"))
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                keyboardFocusRequester.requestFocus()
                                keyboardController?.show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveAccent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Focus Input Keyboard", color = ImmersiveButtonDeep, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "enter")) },
                            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Inject Enter", color = ImmersiveTextMain, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Small custom quick directional keys Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalIconButton(
                                onClick = { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "escape")) },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = ImmersiveBg),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Text("Esc", color = ImmersiveTextMain, fontSize = 9.sp)
                            }
                            FilledTonalIconButton(
                                onClick = { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "tab")) },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = ImmersiveBg),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Text("Tab", color = ImmersiveTextMain, fontSize = 9.sp)
                            }
                        }

                        // Arrow keys group
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalIconButton(onClick = { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "left")) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = ImmersiveBg), modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Left", tint = ImmersiveAccent, modifier = Modifier.size(16.dp))
                            }
                            FilledTonalIconButton(onClick = { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "up")) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = ImmersiveBg), modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = ImmersiveAccent, modifier = Modifier.size(16.dp))
                            }
                            FilledTonalIconButton(onClick = { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "down")) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = ImmersiveBg), modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Down", tint = ImmersiveAccent, modifier = Modifier.size(16.dp))
                            }
                            FilledTonalIconButton(onClick = { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "right")) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = ImmersiveBg), modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ArrowForward, contentDescription = "Right", tint = ImmersiveAccent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // --- 3. Interaction Controls Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ImmersiveBg)
                .drawBehind {
                    drawLine(
                        color = ImmersiveBorder,
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Keyboard toggle action
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { displayKeyboardOption = !displayKeyboardOption }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(ImmersiveSurface, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Keyboard trigger",
                        tint = ImmersiveAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text("Keyboard", color = ImmersiveTextMain, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }

            // Central Dynamic Mouse Mode Button (FAB action element)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onSendCommand(NetworkCommand.MouseClick("left"))
                        }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(ImmersiveAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mouse,
                        contentDescription = "Mouse control primary",
                        tint = ImmersiveButtonDeep,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Text("Mouse Mode", color = ImmersiveAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            // Settings toggle action
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { displaySettingsOption = !displaySettingsOption }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(ImmersiveSurface, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings configuration toggle",
                        tint = ImmersiveTextMain,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text("Settings", color = ImmersiveTextMain, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }

        // --- 4. Bottom Shortcut Strip ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ImmersiveDarker)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ImmersiveBorder.copy(alpha = 0.3f))
                    .border(1.dp, ImmersiveBorder, RoundedCornerShape(6.dp))
                    .clickable { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "ctrl+alt+del")) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Ctrl + Alt + Del",
                    color = ImmersiveAccent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ImmersiveBorder.copy(alpha = 0.3f))
                    .border(1.dp, ImmersiveBorder, RoundedCornerShape(6.dp))
                    .clickable { onSendCommand(NetworkCommand.KeyboardInput(keyCode = "win+l")) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Win + L",
                    color = ImmersiveAccent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ImmersiveAlertRed.copy(alpha = 0.2f))
                    .border(1.dp, ImmersiveAlertRed.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable { onDisconnectRequested() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Power",
                    color = ImmersiveAlertLight,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
