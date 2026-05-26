package com.biplabs.wisp.data

import android.content.Context
import android.net.Uri
import com.biplabs.wisp.bridge.WispNative
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit

class WispRepository(context: Context) {
    private val prefs = context.getSharedPreferences("wispshell", Context.MODE_PRIVATE)

    var cloudUrl: String
        get() = prefs.getString(KEY_CLOUD_URL, DEFAULT_REGISTRY_URL) ?: DEFAULT_REGISTRY_URL
        private set(value) {
            prefs.edit().putString(KEY_CLOUD_URL, value).apply()
        }

    val hasRegistryUrl: Boolean
        get() = prefs.getBoolean(KEY_REGISTRY_CONFIGURED, false)

    val clientDeviceId: String
        get() = identity().deviceId

    val clientPrivateKey: String
        get() = identity().privateKey

    fun savePairingUri(uriText: String) {
        val uri = Uri.parse(uriText)
        val cloud = uri.getQueryParameter("cloud") ?: return
        updateRegistryUrl(cloud)
    }

    fun updateCloudUrl(value: String) {
        updateRegistryUrl(value)
    }

    fun updateRegistryUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        cloudUrl = normalized
        prefs.edit().putBoolean(KEY_REGISTRY_CONFIGURED, normalized.isNotBlank()).apply()
    }

    fun startCodePairing(clientName: String = "Android Tablet"): PairingCodeSession {
        val identity = identity()
        val code = WispNative.generatePairingCode()
        val hash = WispNative.pairingCodeHash(code)
        val expiresAt = Instant.now().plus(2, ChronoUnit.MINUTES).toString()
        postJson(
            "${cloudUrl.trimEnd('/')}/v1/pairing/code/start",
            JSONObject()
                .put("code_hash", hash)
                .put("client_device_id", identity.deviceId)
                .put("client_public_key", identity.publicKey)
                .put("client_name", clientName)
                .put("expires_at", expiresAt)
                .toString(),
        )
        return PairingCodeSession(code = code, expiresAt = expiresAt)
    }

    fun boundDaemons(): List<BoundDaemon> {
        val body = getJson("${cloudUrl.trimEnd('/')}/v1/bindings/for-client/$clientDeviceId")
        val array = JSONArray(body)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    BoundDaemon(
                        bindingId = item.getString("binding_id"),
                        daemonDeviceId = item.getString("daemon_device_id"),
                        daemonPublicKey = item.optString("daemon_public_key"),
                        displayName = item.getString("display_name"),
                        status = item.getString("status"),
                    ),
                )
            }
        }
    }

    fun rendezvous(daemon: BoundDaemon): RendezvousInfo {
        val url = "${cloudUrl.trimEnd('/')}/v1/rendezvous/${daemon.daemonDeviceId}" +
            "?client_device_id=$clientDeviceId&binding_id=${daemon.bindingId}"
        val json = JSONObject(getJson(url))
        return RendezvousInfo(
            daemonDeviceId = json.getString("daemon_device_id"),
            daemonPublicKey = json.getString("daemon_public_key"),
            status = json.getString("status"),
            irohNodeAddrJson = json.optJSONObject("iroh_node_addr")?.toString(),
        )
    }

    fun revokeBinding(bindingId: String) {
        postJson(
            "${cloudUrl.trimEnd('/')}/v1/bindings/$bindingId/revoke",
            JSONObject().toString(),
        )
    }

    fun identity(): DeviceIdentity {
        val existing = prefs.getString(KEY_PRIVATE_KEY, null)
        val json = if (existing.isNullOrBlank()) {
            WispNative.generateDeviceIdentityJson()
        } else {
            WispNative.deviceIdentityFromPrivateKeyJson(existing)
        }
        val parsed = JSONObject(json)
        if (parsed.has("error")) {
            error(parsed.getString("error"))
        }
        val identity = DeviceIdentity(
            deviceId = parsed.getString("device_id"),
            publicKey = parsed.getString("public_key"),
            privateKey = parsed.getString("private_key"),
        )
        prefs.edit()
            .putString(KEY_CLIENT_DEVICE_ID, identity.deviceId)
            .putString(KEY_PUBLIC_KEY, identity.publicKey)
            .putString(KEY_PRIVATE_KEY, identity.privateKey)
            .apply()
        return identity
    }

    private fun getJson(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                error("HTTP $status: $body")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, body: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("content-type", "application/json")
        connection.doOutput = true
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                error("HTTP $status: $response")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val KEY_CLOUD_URL = "cloud_url"
        private const val KEY_REGISTRY_CONFIGURED = "registry_configured"
        private const val KEY_CLIENT_DEVICE_ID = "client_device_id"
        private const val KEY_PUBLIC_KEY = "client_public_key"
        private const val KEY_PRIVATE_KEY = "client_private_key"
        private const val DEFAULT_REGISTRY_URL = "https://wisp.biplabs.com"
    }
}
