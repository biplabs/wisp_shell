package com.biplabs.wisp.terminal

import com.biplabs.wisp.bridge.NativeTerminalCallback
import com.biplabs.wisp.bridge.WispNative
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class NativeP2pTerminalConnection(
    private val rendezvousJson: String,
    private val privateKey: String,
    private val clientDeviceId: String,
    private val bindingId: String,
    private val sessionName: String,
    private val onBytes: (ByteArray, Boolean) -> Unit,
    private val onState: (ConnectionState) -> Unit,
    private val onTransportPathChanged: (TransportPathStatus) -> Unit,
    private val onConnectionError: (String) -> Unit,
) : WispTerminalConnection {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var handle: Long = 0
    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var connectTimeout: ScheduledFuture<*>? = null
    @Volatile private var pendingResize: Pair<Int, Int>? = null

    override fun connect() {
        connectTimeout = scheduler.schedule({
            if (!connected && !closed) {
                onConnectionError("p2p connect timed out")
                onState(ConnectionState.Disconnected)
                close()
            }
        }, CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        executor.execute {
            var lastError: Throwable? = null
            repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
                if (closed) return@execute
                try {
                    onState(ConnectionState.Connecting)
                    val nativeHandle = WispNative.connectP2pTerminal(
                        rendezvousJson = rendezvousJson,
                        privateKey = privateKey,
                        clientDeviceId = clientDeviceId,
                        bindingId = bindingId,
                        sessionName = sessionName,
                        callback = object : NativeTerminalCallback {
                            override fun onState(state: String) {
                                when (state) {
                                    "attached" -> {
                                        connected = true
                                        connectTimeout?.cancel(false)
                                        onState(ConnectionState.Attached)
                                    }
                                    "disconnected" -> {
                                        connectTimeout?.cancel(false)
                                        onState(ConnectionState.Disconnected)
                                    }
                                    else -> onState(ConnectionState.Connecting)
                                }
                            }

                            override fun onTransportPath(path: String, latencyMs: Int) {
                                onTransportPathChanged(
                                    TransportPathStatus(
                                        path = path,
                                        latencyMs = latencyMs.takeIf { it >= 0 },
                                    ),
                                )
                            }

                            override fun onScrollback(data: ByteArray) {
                                onBytes(data, true)
                            }

                            override fun onOutput(data: ByteArray) {
                                onBytes(data, false)
                            }

                            override fun onError(message: String) {
                                onConnectionError(message)
                            }
                        },
                    )
                    if (closed) {
                        if (nativeHandle != 0L) {
                            WispNative.closeTerminal(nativeHandle)
                        }
                        return@execute
                    }
                    handle = nativeHandle
                    pendingResize?.let { (cols, rows) ->
                        pendingResize = null
                        WispNative.resizeTerminal(nativeHandle, cols, rows)
                    }
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
        if (current == 0L) {
            pendingResize = cols to rows
            return
        }
        executor.execute {
            WispNative.resizeTerminal(current, cols, rows)
        }
    }

    override fun close() {
        closed = true
        val current = handle
        handle = 0
        pendingResize = null
        connectTimeout?.cancel(false)
        if (current != 0L) {
            executor.execute {
                WispNative.closeTerminal(current)
            }
        }
        scheduler.shutdownNow()
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 1
        const val CONNECT_TIMEOUT_SECONDS = 20L
    }
}
