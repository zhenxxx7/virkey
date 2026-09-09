package com.virkey.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virkey.app.input.KeySpec
import com.virkey.app.input.LaptopLayout

private val Background = Color(0xFF101315)
private val Deck = Color(0xFF1B2023)
private val KeyFace = Color(0xFF2B3236)
private val Ivory = Color(0xFFEAEDE7)
private val Muted = Color(0xFF919C9D)
private val Accent = Color(0xFF8EDCC0)
private val Outline = Color(0xFF3B4447)

@Composable
fun VirkeyScreen(state: RemoteUiState, onAction: (RemoteAction) -> Unit) {
    var showConnections by rememberSaveable { mutableStateOf(false) }
    val colors = darkColorScheme(
        primary = Accent,
        onPrimary = Background,
        surface = Deck,
        onSurface = Ivory,
        onSurfaceVariant = Muted,
        background = Background,
        onBackground = Ivory,
        outline = Outline,
    )
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().testTag("virkey_screen").background(
                Brush.verticalGradient(listOf(Color(0xFF20272A), Background)),
            ).safeDrawingPadding(),
        ) {
            val compact = maxHeight < 600.dp
            val outerPadding = if (compact) 16.dp else 28.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = outerPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
            ) {
                Header(state, onAction) {
                    onAction(RemoteAction.RefreshDevices)
                    showConnections = true
                }
                Surface(
                    Modifier.fillMaxWidth().weight(1.5f).testTag("keyboard"),
                    shape = RoundedCornerShape(20.dp),
                    color = Deck,
                    border = BorderStroke(1.dp, Outline.copy(alpha = 0.65f)),
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(if (compact) 10.dp else 14.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
                    ) {
                        LaptopLayout.rows.forEachIndexed { rowIndex, row ->
                            Row(
                                Modifier.fillMaxWidth().weight(if (rowIndex == 0) 0.68f else 1f),
                                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
                            ) {
                                row.forEach { key ->
                                    if (key.usage < 0) {
                                        Spacer(Modifier.weight(key.weight).fillMaxHeight())
                                    } else {
                                        LaptopKey(
                                            key = key,
                                            enabled = state.isConnected,
                                            capsLock = state.capsLock,
                                            compact = compact,
                                            functionRow = rowIndex == 0,
                                            onAction = onAction,
                                            modifier = Modifier.weight(key.weight).fillMaxHeight(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("A LITTLE SPACE.\nFULL CONTROL.", color = Muted, fontSize = if (compact) 10.sp else 12.sp, letterSpacing = 1.3.sp, lineHeight = 19.sp)
                        Box(Modifier.width(32.dp).height(2.dp).background(Accent.copy(alpha = 0.65f)))
                        Text(
                            if (state.isConnected) state.connectedName ?: "Connected PC" else "Your PC, within reach.",
                            color = Ivory.copy(alpha = 0.85f),
                            fontSize = if (compact) 11.sp else 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!compact) Text("Bluetooth · No PC app needed", color = Muted, fontSize = 11.sp)
                    }
                    Trackpad(
                        enabled = state.isConnected,
                        onAction = onAction,
                        compact = compact,
                        modifier = Modifier.weight(2.35f).fillMaxHeight(),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 13.dp)) {
                        GestureHint("ONE FINGER", "Move · tap to click", compact)
                        GestureHint("TWO FINGERS", "Scroll · tap for right-click", compact)
                        GestureHint("DRAG", "Hold LEFT + move on pad", compact)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(if (state.isConnected) Accent else Muted, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(state.statusMessage, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("US QWERTY", color = Muted.copy(alpha = 0.75f), fontSize = 10.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.width(16.dp))
                    Text("VIRKEY / 01", color = Muted.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (showConnections) ConnectionDialog(state, onAction) { showConnections = false }
    }
}

@Composable
private fun Header(state: RemoteUiState, onAction: (RemoteAction) -> Unit, openConnections: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(33.dp)) {
            val line = 2.dp.toPx()
            val cell = size.width / 3f
            for (x in 0..2) for (y in 0..1) {
                drawRoundRect(
                    Accent,
                    topLeft = Offset(x * cell + line, y * cell + line),
                    size = androidx.compose.ui.geometry.Size(cell - 2 * line, cell - 2 * line),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(line),
                )
            }
            drawRoundRect(Accent, Offset(line, cell * 2 + line), androidx.compose.ui.geometry.Size(size.width - 2 * line, cell - 2 * line), androidx.compose.ui.geometry.CornerRadius(line))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("VIRKEY", color = Ivory, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
            Text("KEYBOARD + TRACKPAD", color = Muted, fontSize = 9.sp, letterSpacing = 1.8.sp)
        }
        Spacer(Modifier.weight(1f))
        if (state.isConnected) {
            TextButton(onClick = { onAction(RemoteAction.ReleaseAll) }) {
                Text("Release keys", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
        }
        OutlinedButton(
            onClick = openConnections,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (state.isConnected) Accent.copy(alpha = 0.4f) else Outline),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.isConnected) Accent.copy(alpha = 0.07f) else Deck),
        ) {
            Box(Modifier.size(7.dp).background(if (state.isConnected) Accent else Muted, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(if (state.isConnected) state.connectedName ?: "Connected" else "Connect to PC", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 210.dp), color = Ivory, fontSize = 12.sp)
            Spacer(Modifier.width(12.dp))
            Text("›", color = Muted, fontSize = 20.sp)
        }
    }
}

@Composable
private fun LaptopKey(
    key: KeySpec,
    enabled: Boolean,
    capsLock: Boolean,
    compact: Boolean,
    functionRow: Boolean,
    onAction: (RemoteAction) -> Unit,
    modifier: Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val latestAction by rememberUpdatedState(onAction)
    val haptic = LocalHapticFeedback.current
    val face by animateColorAsState(if (pressed) Color(0xFF44655D) else KeyFace, label = "keyFace")
    val shape = RoundedCornerShape(if (compact) 6.dp else 8.dp)
    Box(
        modifier
            .shadow(if (pressed) 0.dp else 2.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(face, face.copy(red = face.red * 0.88f, green = face.green * 0.88f, blue = face.blue * 0.88f))))
            .border(1.dp, if (pressed) Accent.copy(alpha = 0.8f) else Color(0xFF424A4E).copy(alpha = 0.65f), shape)
            .semantics {
                contentDescription = key.label.ifBlank { "Space" }
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) {
                        latestAction(RemoteAction.KeyDown(key.usage))
                        latestAction(RemoteAction.KeyUp(key.usage))
                    }
                    enabled
                }
            }
            .pointerInput(enabled, key.usage) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    latestAction(RemoteAction.KeyDown(key.usage))
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            pointer.consume()
                            if (!pointer.pressed) break
                            if (pointer.position.x < 0 || pointer.position.x > size.width || pointer.position.y < 0 || pointer.position.y > size.height) break
                        }
                    } finally {
                        pressed = false
                        latestAction(RemoteAction.KeyUp(key.usage))
                    }
                }
            }
            .padding(horizontal = if (compact) 6.dp else 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (key.secondary != null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.Start) {
                Text(key.secondary, color = Muted, fontSize = if (compact) 9.sp else 11.sp, lineHeight = 12.sp, maxLines = 1)
                Text(key.label, color = Ivory, fontSize = if (compact) 13.sp else 17.sp, lineHeight = 18.sp, maxLines = 1)
            }
        } else {
            Text(
                key.label,
                color = if (pressed) Color.White else Ivory,
                fontSize = when {
                    functionRow -> if (compact) 9.sp else 11.sp
                    key.label.length > 2 -> if (compact) 10.sp else 12.sp
                    else -> if (compact) 15.sp else 18.sp
                },
                fontWeight = if (key.label.length == 1) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                modifier = if (key.label.length > 2) Modifier.align(Alignment.CenterStart) else Modifier,
            )
        }
        if (key.usage == 0x39) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(4.dp).background(if (capsLock) Accent else Outline, CircleShape))
        if (key.usage == 0x2C) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).width(36.dp).height(2.dp).background(Outline, CircleShape))
        if (key.usage == 0x09 || key.usage == 0x0D) Box(Modifier.align(Alignment.BottomCenter).width(8.dp).height(2.dp).background(Muted.copy(alpha = 0.4f), CircleShape))
    }
}

@Composable
private fun GestureHint(title: String, description: String, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Muted.copy(alpha = 0.8f), fontSize = if (compact) 8.sp else 9.sp, letterSpacing = 1.1.sp)
        Text(description, color = Ivory.copy(alpha = 0.8f), fontSize = if (compact) 10.sp else 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun Trackpad(enabled: Boolean, onAction: (RemoteAction) -> Unit, compact: Boolean, modifier: Modifier) {
    val latestAction by rememberUpdatedState(onAction)
    var touching by remember { mutableStateOf(false) }
    var heldButtons by remember { mutableStateOf(0) }
    val buttonAction: (RemoteAction) -> Unit = { action ->
        when (action) {
            is RemoteAction.MouseDown -> heldButtons = heldButtons or action.button
            is RemoteAction.MouseUp -> heldButtons = heldButtons and action.button.inv()
            else -> Unit
        }
        latestAction(action)
    }
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier.testTag("trackpad").clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF222A2D), Color(0xFF1D2427))))
            .border(1.dp, if (touching) Accent.copy(alpha = 0.55f) else Outline, shape),
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .semantics {
                    contentDescription = "Trackpad. One finger moves the pointer. Tap to click. Two fingers scroll or tap for right-click. Hold the left mouse button while moving to drag."
                    if (!enabled) disabled()
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val scrollUnit = 20.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        touching = true
                        val gesture = TrackpadGesture(
                            startedAt = down.uptimeMillis,
                            scrollDistance = scrollUnit,
                            tapSlop = viewConfiguration.touchSlop,
                        )
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                gesture.update(
                                    time = event.changes.maxOfOrNull { it.uptimeMillis } ?: down.uptimeMillis,
                                    buttonsHeld = heldButtons != 0,
                                    points = event.changes.map {
                                        TouchDelta(
                                            dx = it.position.x - it.previousPosition.x,
                                            dy = it.position.y - it.previousPosition.y,
                                            pressed = it.pressed,
                                            previouslyPressed = it.previousPressed,
                                        )
                                    },
                                ).forEach(latestAction)
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        } finally {
                            touching = false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (!enabled) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("READY WHEN YOU ARE", color = Muted, fontSize = if (compact) 9.sp else 11.sp, letterSpacing = 2.sp)
                    Text("Connect your PC to start", color = Muted.copy(alpha = 0.6f), fontSize = if (compact) 10.sp else 12.sp)
                }
            } else {
                Canvas(Modifier.size(18.dp)) {
                    val stroke = 1.dp.toPx()
                    drawLine(Outline, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke)
                    drawLine(Outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
                }
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 9.dp).width(27.dp).height(2.dp).background(Outline.copy(alpha = 0.8f), CircleShape))
        }
        HorizontalDivider(color = Outline.copy(alpha = 0.8f))
        Row(Modifier.fillMaxWidth().height(if (compact) 30.dp else 39.dp)) {
            MouseButton("LEFT", 1, enabled, buttonAction, Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.fillMaxHeight().width(1.dp).background(Outline.copy(alpha = 0.8f)))
            MouseButton("RIGHT", 2, enabled, buttonAction, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun MouseButton(label: String, button: Int, enabled: Boolean, onAction: (RemoteAction) -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val latestAction by rememberUpdatedState(onAction)
    Box(
        modifier.background(if (pressed) Accent.copy(alpha = 0.15f) else Color.Transparent)
            .semantics {
                contentDescription = "$label mouse button. Hold while using trackpad to drag."
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) {
                        latestAction(RemoteAction.MouseDown(button))
                        latestAction(RemoteAction.MouseUp(button))
                    }
                    enabled
                }
            }
            .pointerInput(enabled, button) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    latestAction(RemoteAction.MouseDown(button))
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            pointer.consume()
                            if (!pointer.pressed) break
                        }
                    } finally {
                        pressed = false
                        latestAction(RemoteAction.MouseUp(button))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) Accent else Muted, fontSize = 9.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun ConnectionDialog(state: RemoteUiState, onAction: (RemoteAction) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(state.isConnected) {
        if (state.isConnected) onAction(RemoteAction.ReleaseAll)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.isConnected) "Your connection" else "Connect your PC") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(state.statusMessage, color = if (state.isConnected) Accent else Muted)
                when {
                    !state.isSupported -> Text("This Android device is not exposing Bluetooth keyboard support. Restart Bluetooth and try again.")
                    !state.permissionsReady -> {
                        Text("Allow Nearby devices so Virkey can pair with your PC as a Bluetooth keyboard and mouse.")
                        Button(onClick = { onAction(RemoteAction.RequestPermissions) }) { Text("Allow Nearby devices") }
                    }
                    !state.bluetoothEnabled -> {
                        Text("Turn on Bluetooth on your tablet and your Windows PC.")
                        Button(onClick = { onAction(RemoteAction.EnableBluetooth) }) { Text("Turn on Bluetooth") }
                    }
                    state.isConnected -> {
                        Text(state.connectedName ?: "Windows PC", color = Ivory, fontWeight = FontWeight.Medium)
                        Text("Keep Virkey open while typing. Hold modifier keys together with another key, just like on a laptop. Your PC controls the keyboard language.")
                        OutlinedButton(onClick = { onAction(RemoteAction.Disconnect) }) { Text("Disconnect") }
                    }
                    else -> {
                        Text("PAIRED DEVICES", color = Muted, fontSize = 10.sp, letterSpacing = 1.4.sp)
                        if (state.pairedDevices.isEmpty()) Text("No paired devices yet. Pair your Windows PC below.", color = Muted)
                        state.pairedDevices.forEach { device ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (device.address == state.selectedAddress) Accent.copy(alpha = 0.1f) else KeyFace)
                                    .clickable { onAction(RemoteAction.Connect(device.address)) }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, color = Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(device.address, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Text("Connect  ›", color = Accent, fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(color = Outline)
                        Text("FIRST-TIME PAIRING", color = Muted, fontSize = 10.sp, letterSpacing = 1.4.sp)
                        Text("1. On Windows, open Settings → Bluetooth & devices → Add device → Bluetooth.\n\n2. Tap the button below, then select this tablet on your PC.\n\n3. Confirm matching pairing codes on both devices. Return here and select your PC.", lineHeight = 21.sp)
                        Button(onClick = { onAction(RemoteAction.PairNewDevice) }) { Text("Pair new Windows PC") }
                        Text("No PC app required. Keep Virkey open during use.", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (state.isConnected) "Start typing" else "Done") } },
        dismissButton = {
            if (state.permissionsReady && state.bluetoothEnabled && !state.isConnected) {
                TextButton(onClick = { onAction(RemoteAction.RefreshDevices) }) { Text("Refresh") }
            }
        },
        containerColor = Deck,
    )
}
