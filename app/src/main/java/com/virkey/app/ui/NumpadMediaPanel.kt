package com.virkey.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virkey.app.input.MediaKeys
import kotlin.math.roundToInt

private val PanelDeck = Color(0xFF1B2023)
private val PanelKeyFace = Color(0xFF2B3236)
private val PanelIvory = Color(0xFFEAEDE7)
private val PanelMuted = Color(0xFF919C9D)
private val PanelAccent = Color(0xFF8EDCC0)
private val PanelOutline = Color(0xFF3B4447)

/** The second keyboard deck: a physical keypad and standard PC media controls. */
@Composable
fun NumpadMediaPanel(
    state: RemoteUiState,
    onAction: (RemoteAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.testTag("numpad_media_panel"),
        shape = RoundedCornerShape(20.dp),
        color = PanelDeck,
        border = BorderStroke(1.dp, PanelOutline.copy(alpha = 0.65f)),
        shadowElevation = 8.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 300.dp
            val condensed = maxHeight < 210.dp
            val padding = if (compact) 10.dp else 18.dp
            val gap = if (compact) 5.dp else 7.dp
            Row(
                Modifier.fillMaxSize().padding(padding),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 28.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().testTag("numpad"),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PanelHeading("NUMBER PAD", compact)
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(5.dp).background(if (state.numLock) PanelAccent else PanelOutline, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.numLock) "NUM LOCK ON" else "NUM LOCK OFF",
                            color = if (state.numLock) PanelAccent else PanelMuted,
                            fontSize = if (compact) 8.sp else 9.sp,
                            letterSpacing = 0.8.sp,
                        )
                    }
                    Layout(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        content = {
                            NumpadKeys.forEach { key ->
                                PanelKey(
                                    label = key.label,
                                    secondary = key.secondary.takeUnless { condensed },
                                    description = "Numpad ${key.description}",
                                    enabled = state.isConnected,
                                    compact = compact,
                                    indicator = if (key.usage == 0x53) state.numLock else null,
                                    homingMark = key.usage == 0x5D,
                                    down = RemoteAction.KeyDown(key.usage),
                                    up = RemoteAction.KeyUp(key.usage),
                                    onAction = onAction,
                                )
                            }
                        },
                    ) { measurables, constraints ->
                        val gapPx = gap.roundToPx()
                        val cellWidth = ((constraints.maxWidth - gapPx * 3) / 4f).coerceAtLeast(0f)
                        val cellHeight = ((constraints.maxHeight - gapPx * 4) / 5f).coerceAtLeast(0f)
                        val placeables = measurables.mapIndexed { index, measurable ->
                            val key = NumpadKeys[index]
                            measurable.measure(
                                Constraints.fixed(
                                    (cellWidth * key.columns + gapPx * (key.columns - 1)).roundToInt(),
                                    (cellHeight * key.rows + gapPx * (key.rows - 1)).roundToInt(),
                                ),
                            )
                        }
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeables.forEachIndexed { index, placeable ->
                                val key = NumpadKeys[index]
                                placeable.placeRelative(
                                    (key.column * (cellWidth + gapPx)).roundToInt(),
                                    (key.row * (cellHeight + gapPx)).roundToInt(),
                                )
                            }
                        }
                    }
                    if (!condensed) {
                        Text(
                            if (state.numLock) "Numbers ready" else "Turn on Num Lock for numbers",
                            color = PanelMuted,
                            fontSize = if (compact) 9.sp else 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(PanelOutline.copy(alpha = 0.6f)))
                Column(
                    Modifier.weight(1.2f).fillMaxHeight().testTag("media_controls"),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
                ) {
                    PanelHeading("SOUND & PLAYBACK", compact)
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        MediaPanelKey("MUTE", "Mute", null, MediaKeys.MUTE, state, compact, onAction, Modifier.weight(1f).fillMaxHeight())
                        MediaPanelKey("−", "Volume down", "VOLUME", MediaKeys.VOLUME_DOWN, state, compact, onAction, Modifier.weight(1f).fillMaxHeight())
                        MediaPanelKey("+", "Volume up", "VOLUME", MediaKeys.VOLUME_UP, state, compact, onAction, Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        MediaPanelKey("|◀", "Previous", "PREVIOUS", MediaKeys.PREVIOUS, state, compact, onAction, Modifier.weight(1f).fillMaxHeight())
                        MediaPanelKey("▶ / Ⅱ", "Play / Pause", "PLAY / PAUSE", MediaKeys.PLAY_PAUSE, state, compact, onAction, Modifier.weight(1.4f).fillMaxHeight(), accent = true)
                        MediaPanelKey("▶|", "Next", "NEXT", MediaKeys.NEXT, state, compact, onAction, Modifier.weight(1f).fillMaxHeight())
                        MediaPanelKey("■", "Stop", "STOP", MediaKeys.STOP, state, compact, onAction, Modifier.weight(1f).fillMaxHeight())
                    }
                    Text(
                        "Controls your PC's active media player",
                        color = PanelMuted,
                        fontSize = if (compact) 9.sp else 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelHeading(label: String, compact: Boolean) {
    Text(label, color = PanelMuted, fontSize = if (compact) 9.sp else 10.sp, letterSpacing = 1.4.sp, maxLines = 1)
}

@Composable
private fun MediaPanelKey(
    label: String,
    description: String,
    secondary: String?,
    usage: Int,
    state: RemoteUiState,
    compact: Boolean,
    onAction: (RemoteAction) -> Unit,
    modifier: Modifier,
    accent: Boolean = false,
) {
    PanelKey(
        label = label,
        secondary = secondary,
        description = "Media $description",
        enabled = state.isConnected,
        compact = compact,
        down = RemoteAction.MediaDown(usage),
        up = RemoteAction.MediaUp(usage),
        onAction = onAction,
        modifier = modifier,
        accent = accent,
        media = true,
    )
}

@Composable
private fun PanelKey(
    label: String,
    secondary: String?,
    description: String,
    enabled: Boolean,
    compact: Boolean,
    down: RemoteAction,
    up: RemoteAction,
    onAction: (RemoteAction) -> Unit,
    modifier: Modifier = Modifier,
    indicator: Boolean? = null,
    homingMark: Boolean = false,
    accent: Boolean = false,
    media: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val latestAction by rememberUpdatedState(onAction)
    val haptic = LocalHapticFeedback.current
    val face by animateColorAsState(
        when {
            pressed -> Color(0xFF44655D)
            accent -> Color(0xFF2C443E)
            else -> PanelKeyFace
        },
        label = "panelKeyFace",
    )
    val shape = RoundedCornerShape(if (compact) 6.dp else 8.dp)
    Box(
        modifier
            .shadow(if (pressed) 0.dp else 2.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(face, face.copy(red = face.red * 0.88f, green = face.green * 0.88f, blue = face.blue * 0.88f))))
            .border(1.dp, if (pressed) PanelAccent.copy(alpha = 0.8f) else PanelOutline, shape)
            .semantics {
                contentDescription = description
                if (indicator != null) {
                    contentDescription = "$description. ${if (indicator) "On" else "Turn on Num Lock for numbers"}"
                }
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) {
                        latestAction(down)
                        latestAction(up)
                    }
                    enabled
                }
            }
            .pointerInput(enabled, down, up) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val pointerDown = awaitFirstDown(requireUnconsumed = false)
                    pointerDown.consume()
                    pressed = true
                    try {
                        latestAction(down)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pointer = event.changes.firstOrNull { it.id == pointerDown.id } ?: break
                            pointer.consume()
                            if (!pointer.pressed) break
                            if (pointer.position.x < 0 || pointer.position.x > size.width || pointer.position.y < 0 || pointer.position.y > size.height) break
                        }
                    } finally {
                        pressed = false
                        latestAction(up)
                    }
                }
            }
            .padding(horizontal = if (compact) 5.dp else 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp),
        ) {
            Text(
                label,
                color = if (accent) PanelAccent else PanelIvory,
                fontSize = when {
                    media && label != "MUTE" -> if (compact) 19.sp else 24.sp
                    label.length > 4 -> if (compact) 10.sp else 12.sp
                    media -> if (compact) 12.sp else 15.sp
                    else -> if (compact) 15.sp else 19.sp
                },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (secondary != null) {
                Text(
                    secondary,
                    color = PanelMuted,
                    fontSize = if (compact) 8.sp else 9.sp,
                    lineHeight = if (compact) 9.sp else 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (indicator != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(4.dp).background(if (indicator) PanelAccent else PanelOutline, CircleShape))
        }
        if (homingMark) {
            Box(Modifier.align(Alignment.BottomCenter).width(8.dp).height(2.dp).background(PanelMuted.copy(alpha = 0.4f), CircleShape))
        }
    }
}

private data class NumpadKey(
    val label: String,
    val usage: Int,
    val column: Int,
    val row: Int,
    val secondary: String? = null,
    val description: String = label,
    val columns: Int = 1,
    val rows: Int = 1,
)

private val NumpadKeys = listOf(
    NumpadKey("Num Lock", 0x53, 0, 0),
    NumpadKey("/", 0x54, 1, 0, description = "Divide"),
    NumpadKey("*", 0x55, 2, 0, description = "Multiply"),
    NumpadKey("−", 0x56, 3, 0, description = "Subtract"),
    NumpadKey("7", 0x5F, 0, 1, "Home"),
    NumpadKey("8", 0x60, 1, 1, "↑"),
    NumpadKey("9", 0x61, 2, 1, "PgUp"),
    NumpadKey("+", 0x57, 3, 1, description = "Add", rows = 2),
    NumpadKey("4", 0x5C, 0, 2, "←"),
    NumpadKey("5", 0x5D, 1, 2),
    NumpadKey("6", 0x5E, 2, 2, "→"),
    NumpadKey("1", 0x59, 0, 3, "End"),
    NumpadKey("2", 0x5A, 1, 3, "↓"),
    NumpadKey("3", 0x5B, 2, 3, "PgDn"),
    NumpadKey("Enter", 0x58, 3, 3, rows = 2),
    NumpadKey("0", 0x62, 0, 4, "Insert", columns = 2),
    NumpadKey(".", 0x63, 2, 4, "Delete", description = "Decimal"),
)
