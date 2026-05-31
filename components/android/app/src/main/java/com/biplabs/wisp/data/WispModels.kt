package com.biplabs.wisp.data

import org.json.JSONObject

data class BoundDaemon(
    val bindingId: String,
    val daemonDeviceId: String,
    val daemonPublicKey: String,
    val displayName: String,
    val status: String,
)

data class SavedTerminalTab(
    val id: String,
    val sessionName: String,
    val title: String?,
    val daemon: BoundDaemon,
)

data class RendezvousInfo(
    val daemonDeviceId: String,
    val daemonPublicKey: String,
    val status: String,
    val irohNodeAddrJson: String?,
    val agentVersion: String?,
    val registryVersion: String?,
) {
    fun toNativeJson(): String {
        return JSONObject()
            .put("daemon_device_id", daemonDeviceId)
            .put("daemon_public_key", daemonPublicKey)
            .put("status", status)
            .put("agent_version", agentVersion ?: JSONObject.NULL)
            .put("registry_version", registryVersion ?: JSONObject.NULL)
            .put(
                "iroh_node_addr",
                irohNodeAddrJson?.let { JSONObject(it) } ?: JSONObject.NULL,
            )
            .toString()
    }
}

data class DeviceIdentity(
    val deviceId: String,
    val publicKey: String,
    val privateKey: String,
)

data class PairingCodeSession(
    val code: String,
    val expiresAt: String,
)
