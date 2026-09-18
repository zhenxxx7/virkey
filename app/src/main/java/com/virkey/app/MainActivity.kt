package com.virkey.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.virkey.app.bluetooth.BluetoothController
import com.virkey.app.bluetooth.BluetoothInputService
import com.virkey.app.ui.RemoteAction
import com.virkey.app.ui.RemoteUiState
import com.virkey.app.ui.VirkeyScreen
import com.virkey.app.ui.ConnectionMode
import com.virkey.app.network.WifiController
import com.virkey.app.network.EncryptedWifiPairingStore
import com.virkey.app.dock.DockItem
import com.virkey.app.dock.DockKind
import com.virkey.app.dock.DockStore
import com.virkey.app.dock.Hotkeys
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var inputService by mutableStateOf<BluetoothInputService?>(null)
    private var bound = false
    private var pendingAction: RemoteAction? = null
    private var pairingJob: Job? = null
    private var acceptsInput = false
    private val wifiController by lazy { WifiController(EncryptedWifiPairingStore(this)) }
    private var connectionMode by mutableStateOf(ConnectionMode.BLUETOOTH)
    private var pendingWifiAddress = ""
    private var pendingWifiPin = ""
    private val dockStore by lazy { DockStore(this) }
    private var shortcutBusy by mutableStateOf(false)
    private var shortcutJob: Job? = null
    private var shortcutEpoch = 0L
    private var shortcutRelease: (() -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        inputService?.controller?.refreshEnvironment()
        if (BluetoothController.hasBluetoothPermissions(this)) resumePendingAction()
        else {
            pendingAction = null
            inputService?.controller?.setMessage("Allow Nearby devices to connect. You can also enable it in Android Settings → Apps → Virkey → Permissions.")
        }
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        inputService?.controller?.refreshEnvironment()
        if (inputService?.controller?.state?.value?.bluetoothEnabled == true) resumePendingAction()
        else pendingAction = null
    }
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        inputService?.controller?.refreshEnvironment()
        if (result.resultCode > 0) inputService?.controller?.setMessage("On Windows: Settings → Bluetooth & devices → Add device → Bluetooth. Choose your tablet's Bluetooth name and confirm the pairing code on both devices.")
        else inputService?.controller?.setMessage("Discoverability was cancelled. Tap Pair new PC to try again.")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            inputService = (binder as BluetoothInputService.LocalBinder).service
            inputService?.controller?.refreshEnvironment()
            resumePendingAction()
        }
        override fun onServiceDisconnected(name: ComponentName) { inputService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionMode = runCatching { ConnectionMode.valueOf(getSharedPreferences("connection", Context.MODE_PRIVATE)
            .getString("last_mode", ConnectionMode.BLUETOOTH.name).orEmpty()) }.getOrDefault(ConnectionMode.BLUETOOTH)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bound = bindService(Intent(this, BluetoothInputService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent {
            val service = inputService
            val bluetoothState = if (service != null) service.controller.state.collectAsStateWithLifecycle().value
                else RemoteUiState(statusMessage = "Opening keyboard…")
            val wifiState by wifiController.state.collectAsStateWithLifecycle()
            val dockItems by dockStore.items.collectAsStateWithLifecycle()
            val state = if (connectionMode == ConnectionMode.BLUETOOTH) bluetoothState else RemoteUiState(
                permissionsReady = true, bluetoothEnabled = true, isSupported = true, isRegistered = true,
                isConnected = wifiState.isConnected, connectedName = wifiState.hostName.ifBlank { "Wi-Fi PC" },
                statusMessage = wifiState.status, capsLock = wifiState.capsLock, numLock = wifiState.numLock,
                locksKnown = wifiState.ledsKnown,
            )
            VirkeyScreen(
                state = state,
                connectionMode = connectionMode,
                wifiState = wifiState,
                onModeChange = ::changeConnectionMode,
                onWifiConnect = { address, pin ->
                    cancelShortcut()
                    pendingWifiAddress = address
                    pendingWifiPin = pin
                    wifiController.connect(address, pin)
                },
                onWifiTrust = {
                    wifiController.state.value.pendingFingerprint?.let { fingerprint ->
                        if (pendingWifiPin.isNotEmpty()) {
                            wifiController.connect(pendingWifiAddress, pendingWifiPin, fingerprint)
                            pendingWifiPin = ""
                        }
                    }
                },
                onWifiDisconnect = { cancelShortcut(); pendingWifiPin = ""; wifiController.disconnect() },
                onWifiDiscover = { wifiController.discover() },
                onWifiReconnect = { address -> cancelShortcut(); pendingWifiPin = ""; wifiController.reconnectSaved(address) },
                onWifiForget = { cancelShortcut(); pendingWifiPin = ""; wifiController.forgetPc() },
                onMedia = { command, position, enabled, mode ->
                    if (acceptsInput && connectionMode == ConnectionMode.WIFI) {
                        // Bind commands to the displayed track, not a newer unseen track.
                        val media = wifiState.nowPlaying
                        wifiController.media(command, media.sessionId, media.trackId, position, enabled, mode)
                    }
                },
                onAction = ::handleAction,
                dockItems = dockItems,
                shortcutBusy = shortcutBusy,
                onDockRun = ::runDockItem,
                onDockSave = dockStore::save,
                onDockRemove = dockStore::remove,
                onDockMove = dockStore::move,
                onAppsRefresh = wifiController::requestApps,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        acceptsInput = true
        inputService?.controller?.refreshEnvironment()
    }

    override fun onPause() {
        cancelShortcut()
        acceptsInput = false
        pairingJob?.cancel()
        inputService?.controller?.releaseAll()
        pendingWifiPin = ""
        wifiController.disconnect()
        super.onPause()
    }

    override fun onDestroy() {
        pairingJob?.cancel()
        wifiController.close()
        if (bound) unbindService(connection)
        inputService = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun handleAction(action: RemoteAction) {
        if (action == RemoteAction.ReleaseAll || action == RemoteAction.Disconnect) cancelShortcut()
        // A delayed gesture callback must not re-press input after onPause's release.
        if (!acceptsInput && when (action) {
                is RemoteAction.KeyDown, is RemoteAction.MediaDown, is RemoteAction.MouseDown,
                is RemoteAction.MovePointer, is RemoteAction.Scroll -> true
                else -> false
            }) return
        if (connectionMode == ConnectionMode.WIFI) {
            if (action == RemoteAction.Disconnect) wifiController.disconnect() else wifiController.send(action)
            return
        }
        val service = inputService
        if (service == null) {
            // Only setup actions may wait for service binding. Never replay stale input.
            pendingAction = when (action) {
                RemoteAction.RequestPermissions, RemoteAction.EnableBluetooth,
                RemoteAction.RefreshDevices, RemoteAction.PairNewDevice, is RemoteAction.Connect -> action
                else -> null
            }
            return
        }
        val controller = service.controller
        when (action) {
            RemoteAction.RequestPermissions -> requestPermissions()
            RemoteAction.EnableBluetooth -> {
                if (!BluetoothController.hasBluetoothPermissions(this)) {
                    pendingAction = action
                    requestPermissions()
                } else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            RemoteAction.RefreshDevices -> controller.refreshEnvironment()
            RemoteAction.PairNewDevice -> if (ensureBluetoothReady(action)) {
                startSession()
                controller.preparePairing()
                pairingJob?.cancel()
                pairingJob = lifecycleScope.launch {
                    val ready = withTimeoutOrNull(15_000) { controller.state.first { it.isRegistered } }
                    if (ready != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
                    } else if (ready == null) controller.setMessage("The tablet's keyboard service did not start. Retry after closing other Bluetooth keyboard apps.")
                }
            }
            is RemoteAction.Connect -> if (ensureBluetoothReady(action)) {
                startSession()
                controller.connect(action.address)
            }
            RemoteAction.Disconnect -> {
                pairingJob?.cancel()
                service.endSession()
            }
            RemoteAction.ReleaseAll -> controller.releaseAll()
            is RemoteAction.KeyDown -> controller.keyDown(action.usage)
            is RemoteAction.KeyUp -> controller.keyUp(action.usage)
            is RemoteAction.MediaDown -> controller.mediaDown(action.usage)
            is RemoteAction.MediaUp -> controller.mediaUp(action.usage)
            is RemoteAction.MovePointer -> controller.movePointer(action.dx, action.dy)
            is RemoteAction.Scroll -> controller.scroll(action.amount)
            is RemoteAction.MouseDown -> controller.mouseDown(action.button)
            is RemoteAction.MouseUp -> controller.mouseUp(action.button)
        }
    }

    private fun changeConnectionMode(mode: ConnectionMode) {
        if (mode == connectionMode) return
        cancelShortcut()
        pairingJob?.cancel()
        pendingAction = null
        pendingWifiPin = ""
        wifiController.disconnect()
        inputService?.controller?.releaseAll()
        if (connectionMode == ConnectionMode.BLUETOOTH) inputService?.controller?.disconnect()
        connectionMode = mode
        getSharedPreferences("connection", Context.MODE_PRIVATE).edit().putString("last_mode", mode.name).apply()
    }

    private fun cancelShortcut() {
        shortcutEpoch++
        shortcutJob?.cancel()
        shortcutJob = null
        shortcutRelease?.invoke()
        shortcutRelease = null
        shortcutBusy = false
    }

    private fun runDockItem(item: DockItem) {
        if (!acceptsInput || shortcutBusy) return
        if (item.kind == DockKind.APP) {
            if (connectionMode == ConnectionMode.WIFI) wifiController.launchApp(item.pcId, item.appId)
            return
        }
        if (runCatching { Hotkeys.parse(item.hotkey) }.isFailure) return
        val bluetooth = inputService?.controller
        val mode = connectionMode
        if (mode == ConnectionMode.WIFI && !wifiController.state.value.isConnected) return
        if (mode == ConnectionMode.BLUETOOTH && bluetooth?.state?.value?.isConnected != true) return
        val epoch = ++shortcutEpoch
        val send: (RemoteAction) -> Unit = { action ->
            if (epoch == shortcutEpoch) {
                if (mode == ConnectionMode.WIFI) wifiController.send(action)
                else when (action) {
                    is RemoteAction.KeyDown -> bluetooth?.keyDown(action.usage)
                    is RemoteAction.KeyUp -> bluetooth?.keyUp(action.usage)
                    else -> Unit
                }
            }
        }
        shortcutRelease = {
            if (mode == ConnectionMode.WIFI) wifiController.send(RemoteAction.ReleaseAll) else bluetooth?.releaseAll()
        }
        shortcutBusy = true
        shortcutJob = lifecycleScope.launch {
            try {
                // Let active touch handlers finish their releases before the chord begins.
                delay(32)
                Hotkeys.play(item.hotkey, send)
            } finally {
                if (epoch == shortcutEpoch) {
                    shortcutRelease = null
                    shortcutBusy = false
                    shortcutJob = null
                }
            }
        }
    }

    private fun ensureBluetoothReady(action: RemoteAction): Boolean {
        inputService?.controller?.refreshEnvironment()
        if (!BluetoothController.hasBluetoothPermissions(this)) {
            pendingAction = action
            requestPermissions()
            return false
        }
        if (inputService?.controller?.state?.value?.bluetoothEnabled != true) {
            pendingAction = action
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        return true
    }

    private fun startSession() {
        ContextCompat.startForegroundService(this, Intent(this, BluetoothInputService::class.java).setAction(BluetoothInputService.ACTION_START))
    }

    private fun requestPermissions() {
        val requested = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requested.isNotEmpty()) permissions.launch(requested.toTypedArray())
        else resumePendingAction()
    }

    private fun resumePendingAction() {
        if (inputService == null) return
        val action = pendingAction ?: return
        pendingAction = null
        handleAction(action)
    }
}
