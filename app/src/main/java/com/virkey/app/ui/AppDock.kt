package com.virkey.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virkey.app.dock.*
import com.virkey.app.network.WifiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppDock(
    items: List<DockItem>, enabled: Boolean, mode: ConnectionMode, wifi: WifiState,
    onRun: (DockItem) -> Unit, onCustomize: () -> Unit, modifier: Modifier = Modifier,
) {
    Row(modifier.testTag("app_dock").fillMaxWidth().height(62.dp)
        .clip(RoundedCornerShape(14.dp)).background(Color(0xFF171D1F)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (items.isEmpty()) item {
                Text("Your apps & soundboard", Modifier.padding(horizontal = 12.dp), color = Color(0xFF919C9D), fontSize = 12.sp)
            }
            items(items, key = { it.id }) { item ->
                val usable = enabled && (item.kind == DockKind.HOTKEY || (mode == ConnectionMode.WIFI && wifi.dockSupported && wifi.pcId == item.pcId))
                Column(Modifier.widthIn(min = 70.dp, max = 104.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp)).clickable(enabled = usable) { onRun(item) }
                    .semantics { contentDescription = "Dock ${item.label}" }.padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    DockIcon(item, Modifier.size(28.dp), usable)
                    Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (usable) 1f else 0.4f))
                }
            }
        }
        TextButton(onClick = onCustomize, modifier = Modifier.testTag("customize_dock")) { Text("Edit dock", fontSize = 11.sp) }
    }
}

@Composable
private fun DockIcon(item: DockItem, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bitmap = remember(item.icon) { decodeDockIcon(item.icon) }
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF293C36)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(),
            alpha = if (enabled) 1f else 0.4f, contentScale = ContentScale.Crop)
        else Text(if (item.kind == DockKind.HOTKEY) "♫" else item.label.take(1).uppercase(), fontSize = 19.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DockEditor(
    items: List<DockItem>, wifi: WifiState, onSave: (DockItem) -> Unit,
    onRemove: (String) -> Unit, onMove: (String, Int) -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<DockItem?>(null) }
    var addingApp by remember { mutableStateOf(false) }
    if (editing != null) {
        key(editing!!.id) {
            DockItemEditor(editing!!, onSave = { onSave(it); editing = null }, onCancel = { editing = null })
        }
        return
    }
    if (addingApp) {
        AppPicker(wifi, onRefresh, onChoose = { app ->
            editing = DockItem(label = app.name.take(40), kind = DockKind.APP, appId = app.id,
                pcId = wifi.pcId, automaticIcon = encodeDockIcon(app.icon))
            addingApp = false
        }, onCancel = { addingApp = false })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Your dock") },
        text = {
            Column {
                Text("Apps open over Wi-Fi. Hotkeys work over Bluetooth or Wi-Fi.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { addingApp = true; onRefresh() }, enabled = items.size < MAX_DOCK_ITEMS,
                        modifier = Modifier.testTag("add_dock_app")) { Text("+ App") }
                    TextButton(onClick = { editing = DockItem(label = "Sound", kind = DockKind.HOTKEY, hotkey = "Ctrl+Alt+1") },
                        enabled = items.size < MAX_DOCK_ITEMS, modifier = Modifier.testTag("add_dock_hotkey")) { Text("+ Sound / hotkey") }
                }
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DockIcon(item, Modifier.size(30.dp))
                            TextButton(onClick = { editing = item }, modifier = Modifier.weight(1f).testTag("edit_${item.id}")) {
                                Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { onMove(item.id, -1) }, enabled = items.first().id != item.id,
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.width(32.dp).semantics { contentDescription = "Move ${item.label} left" }) { Text("←") }
                            TextButton(onClick = { onMove(item.id, 1) }, enabled = items.last().id != item.id,
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.width(32.dp).semantics { contentDescription = "Move ${item.label} right" }) { Text("→") }
                            TextButton(onClick = { onRemove(item.id) }, contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.width(36.dp).semantics { contentDescription = "Remove ${item.label}" }) { Text("×") }
                        }
                    }
                }
                if (items.isEmpty()) Text("Add your favorite apps or a Voicemod sound shortcut.", fontSize = 12.sp)
                Text("${items.size} / $MAX_DOCK_ITEMS buttons", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun AppPicker(wifi: WifiState, onRefresh: () -> Unit, onChoose: (PcApp) -> Unit, onCancel: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Choose a PC app") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!wifi.isConnected || !wifi.dockSupported) {
                Text("Connect over Wi-Fi with Virkey Host 0.4.0 or newer to choose PC apps.")
            } else {
                OutlinedTextField(search, { search = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.testTag("app_search"))
                Text(wifi.appMessage, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(wifi.apps.filter { it.name.contains(search, ignoreCase = true) }, key = { it.id }) { app ->
                        Row(Modifier.fillMaxWidth().clickable { onChoose(app) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (app.icon != null) Image(app.icon.asImageBitmap(), null, Modifier.size(28.dp))
                            Text(app.name, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text("Missing app? Add its .exe or shortcut under Extra apps in Virkey Host, then refresh.", fontSize = 11.sp)
                TextButton(onClick = onRefresh, enabled = !wifi.appsLoading) { Text("Refresh apps") }
            }
        }
    }, confirmButton = { TextButton(onClick = onCancel) { Text("Back") } })
}

@Composable
private fun DockItemEditor(item: DockItem, onSave: (DockItem) -> Unit, onCancel: () -> Unit) {
    var label by rememberSaveable { mutableStateOf(item.label) }
    var hotkey by rememberSaveable { mutableStateOf(item.hotkey) }
    var customIcon by rememberSaveable { mutableStateOf(item.customIcon) }
    var imageError by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try { customIcon = withContext(Dispatchers.IO) { importDockIcon(context, uri) }; imageError = "" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { imageError = error.message ?: "Cannot load image" }
            finally { importing = false }
        }
    }
    val shortcutError = if (item.kind == DockKind.HOTKEY) runCatching { Hotkeys.parse(hotkey) }.exceptionOrNull()?.message else null
    AlertDialog(onDismissRequest = onCancel, title = { Text(if (item.kind == DockKind.APP) "App button" else "Sound / hotkey button") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DockIcon(item.copy(label = label, customIcon = customIcon), Modifier.size(56.dp))
                OutlinedTextField(label, { label = it.take(40) }, label = { Text("Button name") }, singleLine = true, modifier = Modifier.testTag("dock_label"))
                if (item.kind == DockKind.HOTKEY) {
                    OutlinedTextField(hotkey, { hotkey = it.take(80) }, label = { Text("Hotkey") }, placeholder = { Text("Ctrl+Alt+1") },
                        singleLine = true, isError = shortcutError != null, modifier = Modifier.testTag("dock_hotkey"))
                    Text(shortcutError ?: "In Voicemod, assign this same keybind to a sound, voice or control. Keep Voicemod running on your PC.",
                        fontSize = 12.sp, color = if (shortcutError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    TextButton(onClick = { imagePicker.launch("image/*") }, enabled = !importing) { Text(if (importing) "Loading…" else "Choose image") }
                    TextButton(onClick = { customIcon = ""; imageError = "" }, enabled = customIcon.isNotEmpty()) {
                        Text(if (item.kind == DockKind.APP) "Auto icon" else "Reset icon")
                    }
                }
                if (imageError.isNotEmpty()) Text(imageError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            Button(onClick = { onSave(item.copy(label = label.trim(), hotkey = hotkey.trim(), customIcon = customIcon)) },
                enabled = label.isNotBlank() && shortcutError == null && !importing, modifier = Modifier.testTag("save_dock_item")) { Text("Save") }
        }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
