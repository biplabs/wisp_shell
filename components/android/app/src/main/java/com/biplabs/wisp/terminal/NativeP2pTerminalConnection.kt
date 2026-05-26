package com.biplabs.wisp.terminal

import com.biplabs.wisp.bridge.NativeTerminalCallback
import com.biplabs.wisp.bridge.WispNative
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NativeP2pTerminalConnection(
    private val rendezvousJson: String,
    private val privateKey: String,
    private val clientDeviceId: String,
    private val bindingId: String,
    private val onBytes: (ByteArray) -> Unit,
    private val onState: (ConnectionState) -> Unit,
    private val onConnectionError: (String) -> Unit,
) : WispTerminalConnection {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var handle: Long = 0
    @Volatile private var closed = false

    override fun connect() {
        executor.execute {
            var lastError: Throwable? = null
            repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
                if (closed) return@execute
                try {
                    onState(ConnectionState.Connecting)
                    handle = WispNative.connectP2pTerminal(
                        rendezvousJson = rendezvousJson,
                        privateKey = privateKey,
                        clientDeviceId = clientDeviceId,
                        bindingId = bindingId,
                        callback = object : NativeTerminalCallback {
                            override fun onState(state: String) {
                                when (state) {
                                    "attached" -> onState(ConnectionState.Attached)
                                    "disconnected" -> onState(ConnectionState.Disconnected)
                                    else -> onState(ConnectionState.Connecting)
                                }
                            }

                            override fun onOutput(data: ByteArray) {
                                onBytes(data)
                            }

                            override fun onError(message: String) {
                                onConnectionError(message)
                            }
                        },
                    )
                    return@execute
                } catch (error: Throwable) {
                    lastError = error
                    if (attempt < MAX_CONNECT_ATTEMPTS - 1) {
                        onConnectionError("p2p connect failed, retrying: ${error.message ?: "unknown error"}")
                        try {
                            Thread.sleep((attempt + 1) * 750L)
                        } catch (_: InterruptedException) {
                            return@execute
                        }
                    }
                }
            }
            onConnectionError(lastError?.message ?: "native p2p connection failed")
            onState(ConnectionState.Disconnected)
        }
    }

    override fun sendInput(data: String) {
        sendBytes(data.toByteArray(StandardCharsets.UTF_8))
    }

    override fun sendBytes(bytes: ByteArray) {
        val current = handle
        if (current == 0L) return
        executor.execute {
            WispNative.sendTerminalInput(current, bytes)
        }
    }

    override fun resize(cols: Int, rows: Int) {
        val current = handle
        if (current == 0L) return
        executor.execute {
            WispNative.resizeTerminal(current, cols, rows)
        }
    }

    override fun close() {
        closed = true
        val current = handle
        handle = 0
        if (current != 0L) {
            executor.execute {
                WispNative.closeTerminal(current)
            }
        }
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 3
    }
}
