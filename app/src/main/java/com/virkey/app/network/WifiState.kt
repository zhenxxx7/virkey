package com.virkey.app.network

import android.graphics.Bitmap

data class WifiHost(val address: String, val name: String)

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
