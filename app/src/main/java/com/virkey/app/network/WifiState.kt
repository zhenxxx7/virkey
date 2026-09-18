package com.virkey.app.network

import android.graphics.Bitmap
import com.virkey.app.dock.PcApp

data class WifiHost(val address: String, val name: String, val fingerprint: String? = null)

data class WifiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val hostName: String = "",
    val status: String = "Connect to Virkey Host on your PC",
    val pendingFingerprint: String? = null,
    val capsLock: Boolean = false,
    val numLock: Boolean = false,
    val ledsKnown: Boolean = false,
    val nowPlaying: NowPlayingState = NowPlayingState(),
    val hosts: List<WifiHost> = emptyList(),
    val isDiscovering: Boolean = false,
    val dockSupported: Boolean = false,
    val pcId: String = "",
    val apps: List<PcApp> = emptyList(),
    val appsLoading: Boolean = false,
    val appMessage: String = "",
    val savedPc: SavedWifiPc? = null,
    val loadingSavedPc: Boolean = false,
    val pairingRequired: Boolean = false,
    val rememberedConnection: Boolean = false,
)

data class NowPlayingState(
    val available: Boolean = false,
    val sessionId: String = "",
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val player: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val canStop: Boolean = false,
    val canSeek: Boolean = false,
    val canShuffle: Boolean = false,
    val canRepeat: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    val artwork: Bitmap? = null,
)
