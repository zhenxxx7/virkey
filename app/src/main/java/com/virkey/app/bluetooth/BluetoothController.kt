package com.virkey.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.virkey.app.input.ConsumerState
import com.virkey.app.input.HidDescriptor
import com.virkey.app.input.KeyboardState
import com.virkey.app.input.MouseReports
import com.virkey.app.ui.RemoteDevice
import com.virkey.app.ui.RemoteUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** All events, including HID callbacks, run on the main thread to preserve report order. */
@SuppressLint("MissingPermission")
class BluetoothController(private val context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutableState = MutableStateFlow(RemoteUiState())
    val state = mutableState.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val keyboard = KeyboardState()
    private val consumer = ConsumerState()
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var pendingAddress: String? = null
    private var requestingProxy = false
    private var registering = false
    private var active = false
    private var closed = false
    private var mouseButtons = 0
    private var ledState: Byte = 0
    private var bootMode = false
    private val prefs = context.getSharedPreferences("virkey_devices", Context.MODE_PRIVATE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (adapterState == BluetoothAdapter.STATE_TURNING_OFF || adapterState == BluetoothAdapter.STATE_OFF) {
                    clearLocalInput()
                    host = null
                    registering = false
                    mutableState.update { it.copy(isRegistered = false, isConnected = false, connectedName = null, statusMessage = "Bluetooth is off") }
                }
            }
            refreshEnvironment()
            if (active && state.value.bluetoothEnabled && state.value.permissionsReady) acquireProfile()
        }
    }

    private val proxyTimeout = Runnable {
        if (active && !state.value.isRegistered) {
            requestingProxy = false
            registering = false
            setMessage("Keyboard service did not become ready. Close other Bluetooth keyboard apps, then try connecting again. This tablet's HID support still needs verification.")
        }
    }

    private val connectionTimeout = Runnable {
        if (!state.value.isConnected && pendingAddress != null) {
            setMessage("Windows has not connected yet. Open Windows Bluetooth settings; if needed, remove the tablet there and pair again.")
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            requestingProxy = false
            if (closed || !active) {
                adapter?.closeProfileProxy(profile, proxy)
                return
            }
            hid = proxy as? BluetoothHidDevice
            registerKeyboard()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            registering = false
            requestingProxy = false
            host = null
            clearLocalInput()
            mutableState.update { it.copy(isRegistered = false, isConnected = false, connectedName = null, statusMessage = "Bluetooth keyboard service disconnected. Tap Connect to retry.") }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed) return
            registering = false
            handler.removeCallbacks(proxyTimeout)
            if (!active) {
                if (registered) safely { hid?.unregisterApp() }
                return
            }
            mutableState.update { it.copy(isRegistered = registered, statusMessage = if (registered) "Ready. Pair from Windows or choose a paired PC." else "Keyboard session ended. Tap Connect to start again.") }
            if (registered) {
                pendingAddress?.let { connectNow(it) }
            } else {
                host = null
                clearLocalInput()
                mutableState.update { it.copy(isConnected = false, connectedName = null) }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, connectionState: Int) {
            if (closed) return
            when (connectionState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!active || (host != null && host?.address != device.address) ||
                        (pendingAddress != null && pendingAddress != device.address)) {
                        safely { hid?.disconnect(device) }
                        return
                    }
                    handler.removeCallbacks(connectionTimeout)
                    host = device
                    pendingAddress = null
                    bootMode = false
                    clearLocalInput()
                    prefs.edit().putString("last_address", device.address).apply()
                    mutableState.update { it.copy(isConnected = true, connectedName = device.name ?: "Windows PC", selectedAddress = device.address, statusMessage = "Connected. Your tablet is now a keyboard and trackpad.") }
                    // Clear host state on every fresh connection; never replay pre-disconnect input.
                    releaseAll()
                }
                BluetoothProfile.STATE_CONNECTING -> setMessage("Connecting to ${device.name ?: "PC"}…")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == null || host?.address == device.address) {
                        host = null
                        clearLocalInput()
                        mutableState.update { it.copy(isConnected = false, connectedName = null, statusMessage = "Disconnected. Select your PC to reconnect.") }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val reportId = id.toInt() and 0xff
            val report = when {
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && reportId == HidDescriptor.KEYBOARD_REPORT_ID -> keyboard.report()
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && reportId == HidDescriptor.MOUSE_REPORT_ID -> MouseReports.encode(mouseButtons).let { if (bootMode) it.copyOf(3) else it }
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && reportId == HidDescriptor.CONSUMER_REPORT_ID && !bootMode -> consumer.report()
                type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && reportId == HidDescriptor.KEYBOARD_REPORT_ID -> byteArrayOf(ledState)
                else -> null
            }
            safely {
                if (report == null) hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                else hid?.replyReport(device, type, id, if (bufferSize > 0 && bufferSize < report.size) report.copyOf(bufferSize) else report)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && validLedReport(id, data)) {
                updateLeds(data[0])
                safely { hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
            } else safely { hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM) }
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            if (validLedReport(reportId, data)) updateLeds(data[0])
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            bootMode = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE
            // Clear all state even in boot mode, where consumer reports are not defined.
            // Returning to report mode sends neutral reports, never old media commands.
            releaseAll()
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            host = null
            pendingAddress = null
            clearLocalInput()
            mutableState.update { it.copy(isConnected = false, connectedName = null, statusMessage = "Windows removed the keyboard connection. Pair again to reconnect.") }
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        val permitted = hasBluetoothPermissions(context)
        val enabled = permitted && safely(false) { adapter?.isEnabled == true }
        val devices = if (enabled) safely(emptyList()) {
            adapter?.bondedDevices.orEmpty().map { RemoteDevice(it.address, it.name ?: "Bluetooth device") }.sortedBy { it.name.lowercase() }
        } else emptyList()
        mutableState.update { it.copy(permissionsReady = permitted, bluetoothEnabled = enabled, isSupported = adapter != null,
            pairedDevices = devices, selectedAddress = it.selectedAddress ?: prefs.getString("last_address", null)) }
        if (!permitted || !enabled) {
            clearLocalInput()
            host = null
            mutableState.update { it.copy(isConnected = false, connectedName = null, isRegistered = false) }
        }
    }

    fun start() {
        refreshEnvironment()
        if (!state.value.permissionsReady || !state.value.bluetoothEnabled) return
        active = true
        acquireProfile()
    }

    fun preparePairing() {
        pendingAddress = null
        if (host != null) disconnect()
        start()
    }

    private fun acquireProfile() {
        if (hid != null) {
            registerKeyboard()
            return
        }
        if (requestingProxy) return
        requestingProxy = true
        setMessage("Starting Bluetooth keyboard…")
        val requested = safely(false) { adapter?.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE) == true }
        if (!requested) {
            requestingProxy = false
            setMessage("This Android Bluetooth stack did not provide the keyboard profile. Retry after turning Bluetooth off and on.")
        } else {
            handler.removeCallbacks(proxyTimeout)
            handler.postDelayed(proxyTimeout, 12_000)
        }
    }

    private fun registerKeyboard() {
        if (state.value.isRegistered || registering || !active) return
        registering = true
        val settings = BluetoothHidDeviceAppSdpSettings("Virkey", "Laptop keyboard and trackpad", "Virkey", BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.bytes)
        val accepted = safely(false) { hid?.registerApp(settings, null, null, context.mainExecutor, callback) == true }
        if (!accepted) {
            registering = false
            setMessage("Could not register the keyboard. Close any other Bluetooth keyboard app and try again.")
        } else {
            handler.removeCallbacks(proxyTimeout)
            handler.postDelayed(proxyTimeout, 12_000)
        }
    }

    fun connect(address: String) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return
        refreshEnvironment()
        if (state.value.pairedDevices.none { it.address == address }) {
            setMessage("Pair this PC in Windows Bluetooth settings first.")
            return
        }
        if (host?.address == address && state.value.isConnected) return
        if (host != null) {
            setMessage("Disconnect the current PC before switching devices.")
            return
        }
        pendingAddress = address
        mutableState.update { it.copy(selectedAddress = address) }
        start()
        if (state.value.isRegistered) connectNow(address)
    }

    private fun connectNow(address: String) {
        val device = safely<BluetoothDevice?>(null) { adapter?.getRemoteDevice(address) } ?: return
        setMessage("Connecting to ${device.name ?: "PC"}…")
        val accepted = safely(false) { hid?.connect(device) == true }
        if (!accepted) setMessage("Could not start the connection. Try connecting from Windows Bluetooth settings.")
        handler.removeCallbacks(connectionTimeout)
        handler.postDelayed(connectionTimeout, 15_000)
    }

    fun keyDown(usage: Int) { if (state.value.isConnected) sendKeyboard(keyboard.press(usage)) }
    fun keyUp(usage: Int) { sendKeyboard(keyboard.release(usage)) }
    fun mediaDown(usage: Int) {
        if (state.value.isConnected && !bootMode) consumer.press(usage).forEach(::sendConsumer)
    }
    fun mediaUp(usage: Int) { consumer.release(usage).forEach(::sendConsumer) }
    fun movePointer(dx: Int, dy: Int) { if (state.value.isConnected) MouseReports.chunked(mouseButtons, dx, dy).forEach(::sendMouse) }
    fun scroll(amount: Int) { if (state.value.isConnected && !bootMode) MouseReports.chunked(mouseButtons, 0, 0, amount).forEach(::sendMouse) }
    fun mouseDown(button: Int) {
        if (!state.value.isConnected) return
        mouseButtons = mouseButtons or (button and 7)
        sendMouse(MouseReports.encode(mouseButtons))
    }
    fun mouseUp(button: Int) {
        mouseButtons = mouseButtons and button.inv()
        sendMouse(MouseReports.encode(mouseButtons))
    }

    fun releaseAll() {
        sendKeyboard(keyboard.releaseAll())
        mouseButtons = 0
        sendMouse(MouseReports.encode(0))
        sendConsumer(consumer.releaseAll())
    }

    // Bluetooth's boot protocol retains the mandatory keyboard/mouse IDs (unlike USB).
    private fun sendKeyboard(report: ByteArray) = send(HidDescriptor.KEYBOARD_REPORT_ID, report)
    private fun sendMouse(report: ByteArray) = send(HidDescriptor.MOUSE_REPORT_ID, if (bootMode) report.copyOf(3) else report)
    private fun sendConsumer(report: ByteArray) {
        if (!bootMode) send(HidDescriptor.CONSUMER_REPORT_ID, report)
    }
    private fun send(id: Int, report: ByteArray) {
        val device = host ?: return
        if (!state.value.isConnected) return
        if (!safely(false) { hid?.sendReport(device, id, report) == true }) {
            // Fail closed: sending a new press after a failed release could leave a stuck modifier.
            clearLocalInput()
            safely { hid?.disconnect(device) }
            host = null
            mutableState.update { it.copy(isConnected = false, connectedName = null, statusMessage = "Input delivery failed. Reconnect your PC to reset the keyboard.") }
        }
    }

    fun disconnect() {
        pendingAddress = null
        handler.removeCallbacks(connectionTimeout)
        releaseAll()
        host?.let { device -> safely { hid?.disconnect(device) } }
        host = null
        clearLocalInput()
        mutableState.update { it.copy(isConnected = false, connectedName = null, statusMessage = "Disconnected") }
    }

    fun stop() {
        active = false
        disconnect()
        handler.removeCallbacks(proxyTimeout)
        registering = false
        safely { hid?.unregisterApp() }
        hid?.let { safely { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        hid = null
        requestingProxy = false
        mutableState.update { it.copy(isRegistered = false, statusMessage = "Keyboard session stopped") }
    }

    fun close() {
        stop()
        closed = true
        handler.removeCallbacksAndMessages(null)
        context.unregisterReceiver(receiver)
    }

    fun setMessage(message: String) { mutableState.update { it.copy(statusMessage = message) } }
    private fun clearLocalInput() {
        keyboard.releaseAll()
        consumer.releaseAll()
        mouseButtons = 0
        updateLeds(0)
    }
    private fun validLedReport(id: Byte, data: ByteArray) = data.size == 1 &&
        (id.toInt() and 0xff) == HidDescriptor.KEYBOARD_REPORT_ID
    private fun updateLeds(value: Byte) {
        ledState = value
        mutableState.update { it.copy(capsLock = value.toInt() and 2 != 0, numLock = value.toInt() and 1 != 0) }
    }
    private inline fun <T> safely(default: T, operation: () -> T): T = try { operation() } catch (_: SecurityException) {
        setMessage("Bluetooth permission is missing. Allow Nearby devices to continue.")
        default
    } catch (_: IllegalStateException) {
        setMessage("Bluetooth is unavailable. Turn it on and reconnect.")
        default
    }
    private inline fun safely(operation: () -> Unit) { safely(Unit, operation) }

    companion object {
        fun hasBluetoothPermissions(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }
}
