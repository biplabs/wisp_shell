package com.biplabs.wisp.bridge

import android.content.Context

object WispNative {
    init {
        System.loadLibrary("wispshell_core")
    }

    external fun initializeAndroidContext(context: Context)
    external fun generateDeviceIdentityJson(): String
    external fun deviceIdentityFromPrivateKeyJson(privateKey: String): String
    external fun generatePairingCode(): String
    external fun pairingCodeHash(code: String): String
    external fun connectP2pTerminal(
        rendezvousJson: String,
        privateKey: String,
        clientDeviceId: String,
        bindingId: String,
        sessionName: String,
        callback: NativeTerminalCallback,
    ): Long
    external fun sendTerminalInput(handle: Long, data: ByteArray)
    external fun resizeTerminal(handle: Long, cols: Int, rows: Int)
    external fun closeTerminal(handle: Long)
}

interface NativeTerminalCallback {
    fun onState(state: String)
    fun onTransportPath(path: String, latencyMs: Int)
    fun onScrollback(data: ByteArray)
    fun onOutput(data: ByteArray)
    fun onError(message: String)
}
