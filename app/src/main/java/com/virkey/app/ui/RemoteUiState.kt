package com.virkey.app.ui

data class RemoteDevice(val address: String, val name: String)

data class RemoteUiState(
    val permissionsReady: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val isSupported: Boolean = true,
    val isRegistered: Boolean = false,
    val isConnected: Boolean = false,
    val connectedName: String? = null,
    val statusMessage: String = "Ready to connect",
    val pairedDevices: List<RemoteDevice> = emptyList(),
    val selectedAddress: String? = null,
    val capsLock: Boolean = false,
    val numLock: Boolean = false,
)
