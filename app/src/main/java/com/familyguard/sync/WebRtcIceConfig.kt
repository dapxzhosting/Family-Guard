package com.familyguard.sync

import com.familyguard.BuildConfig
import org.webrtc.PeerConnection

object WebRtcIceConfig {

    fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val turnHost = BuildConfig.TURN_HOST
        val turnUsername = BuildConfig.TURN_USERNAME
        val turnCredential = BuildConfig.TURN_CREDENTIAL

        if (turnHost.isNotBlank()) {
            servers.add(
                PeerConnection.IceServer.builder("stun:$turnHost:80").createIceServer()
            )
            listOf(
                "turn:$turnHost:80",
                "turn:$turnHost:80?transport=tcp",
                "turn:$turnHost:443",
                "turns:$turnHost:443?transport=tcp"
            ).forEach { url ->
                servers.add(
                    PeerConnection.IceServer.builder(url)
                        .setUsername(turnUsername)
                        .setPassword(turnCredential)
                        .createIceServer()
                )
            }
        }

        return servers
    }

    fun buildRtcConfiguration(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
    }
}
