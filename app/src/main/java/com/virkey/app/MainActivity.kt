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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var inputService by mutableStateOf<BluetoothInputService?>(null)
    private var bound = false
    private var pendingAction: RemoteAction? = null
    private var pairingJob: Job? = null

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
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bound = bindService(Intent(this, BluetoothInputService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent {
            val service = inputService
            val state = if (service != null) service.controller.state.collectAsStateWithLifecycle().value
                else RemoteUiState(statusMessage = "Opening keyboard…")
            VirkeyScreen(state, ::handleAction)
        }
    }

    override fun onResume() {
        super.onResume()
        inputService?.controller?.refreshEnvironment()
    }

    override fun onPause() {
        pairingJob?.cancel()
        inputService?.controller?.releaseAll()
        super.onPause()
    }

    override fun onDestroy() {
        pairingJob?.cancel()
        if (bound) unbindService(connection)
        inputService = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun handleAction(action: RemoteAction) {
        val service = inputService
        if (service == null) {
            pendingAction = action
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
            is RemoteAction.MovePointer -> controller.movePointer(action.dx, action.dy)
            is RemoteAction.Scroll -> controller.scroll(action.amount)
            is RemoteAction.MouseDown -> controller.mouseDown(action.button)
            is RemoteAction.MouseUp -> controller.mouseUp(action.button)
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
