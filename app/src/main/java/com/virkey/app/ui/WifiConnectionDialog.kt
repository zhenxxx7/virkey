package com.virkey.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virkey.app.network.WifiState
import java.util.Locale

@Composable
fun WifiConnectionDialog(
    state: WifiState,
    onConnect: (String, String) -> Unit,
    onTrust: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
    onDiscover: () -> Unit = {},
) {
    var address by rememberSaveable { mutableStateOf("") }
    // Keep the short-lived pairing PIN out of saved instance state.
    var pin by remember { mutableStateOf("") }
    val fingerprint = state.pendingFingerprint
    val canConnect = !state.isConnecting && address.isNotBlank() && pin.length == 6
    val dismiss = {
        pin = ""
        // Cancel pending discovery/pairing as well as closing the dialog.
        // Dismissing an established connection must leave input connected.
        if (!state.isConnected) onDisconnect()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (fingerprint != null) "Confirm your PC" else "Wi-Fi connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    fingerprint != null -> {
                        Text("Compare this fingerprint with Virkey Host on your PC. Continue only when both match.")
                        Text(fingerprint.filter { it.isLetterOrDigit() }.take(12).uppercase(Locale.ROOT).chunked(4).joinToString(" "),
                            fontFamily = FontFamily.Monospace, fontSize = 26.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("wifi_fingerprint"))
                        Text("Your pairing code will be sent after confirmation.", fontSize = 12.sp)
                    }
                    state.isConnected -> {
                        Text("Connected to ${state.hostName.ifBlank { "your PC" }}")
                        Text("Keyboard, trackpad and now playing are ready.")
                    }
                    else -> {
                        Text("Open Virkey Host.exe on your PC. Keep both devices on the same Wi-Fi network, then enter the address and pairing code shown by the host.")
                        OutlinedButton(onClick = onDiscover, enabled = !state.isConnecting && !state.isDiscovering,
                            modifier = Modifier.testTag("wifi_discover")) {
                            Text(if (state.isDiscovering) "Finding PCs…" else "Find PCs")
                        }
                        state.hosts.take(5).forEach { host ->
                            TextButton(onClick = { address = host.address }, enabled = !state.isConnecting) {
                                Text("${host.name.ifBlank { "Windows PC" }} · ${host.address}")
                            }
                        }
                        OutlinedTextField(
                            value = address, onValueChange = { address = it.trim() },
                            enabled = !state.isConnecting, label = { Text("PC address") },
                            placeholder = { Text("192.168.1.10") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().testTag("wifi_address"),
                        )
                        OutlinedTextField(
                            value = pin, onValueChange = { pin = it.filter { character -> character in '0'..'9' }.take(6) },
                            enabled = !state.isConnecting, label = { Text("6-digit pairing code") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth().testTag("wifi_pin"),
                        )
                    }
                }
                Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.testTag("wifi_status"))
            }
        },
        confirmButton = {
            when {
                fingerprint != null -> Button(onClick = onTrust, modifier = Modifier.testTag("wifi_trust")) { Text("Trust & pair") }
                state.isConnected -> TextButton(onClick = onDisconnect) { Text("Disconnect") }
                else -> Button(onClick = { onConnect(address, pin) }, enabled = canConnect,
                    modifier = Modifier.testTag("wifi_connect")) { Text(if (state.isConnecting) "Connecting…" else "Connect") }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(if (state.isConnected) "Done" else "Cancel") } },
    )
}
