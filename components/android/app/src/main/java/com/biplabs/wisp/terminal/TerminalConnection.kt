package com.biplabs.wisp.terminal

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TerminalConnection(
    private val host: String,
    private val port: Int,
    private val sessionName: String = "main",
    private val onOutput: (String) -> Unit,
    private val onBytes: (ByteArray, Boolean) -> Unit = { _, _ -> },
    private val onState: (ConnectionState) -> Unit = {},
) : WispTerminalConnection {
    private val readExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    @Volatile private var sessionId: String? = null

    override fun connect() {
        readExecutor.execute {
            try {
                onState(ConnectionState.Connecting)
                onOutput("Connecting to $host:$port\r\n")
                val connected = Socket(host, port)
                socket = connected
                writer = OutputStreamWriter(connected.getOutputStream(), StandardCharsets.UTF_8)
                sendJson(
                    JSONObject()
                        .put("type", "attach")
                        .put("session_name", sessionName)
                        .put("cols", 80)
                        .put("rows", 24),
                )
                readLoop(connected)
            } catch (error: Exception) {
                onState(ConnectionState.Disconnected)
                onOutput("\r\n[connection failed: ${error.message}]\r\n")
            }
        }
    }

    override fun sendInput(data: String) {
        sendBytes(data.toByteArray(StandardCharsets.UTF_8))
    }

    override fun sendBytes(bytes: ByteArray) {
        val currentSession = sessionId ?: return
        writeExecutor.execute {
            val encoded = Base64.encodeToString(
                bytes,
                Base64.NO_WRAP,
            )
            sendJson(
                JSONObject()
                    .put("type", "input")
                    .put("session_id", currentSession)
                    .put("data_b64", encoded),
            )
        }
    }

    override fun resize(cols: Int, rows: Int) {
        val currentSession = sessionId ?: return
        writeExecutor.execute {
            sendJson(
                JSONObject()
                    .put("type", "resize")
                    .put("session_id", currentSession)
                    .put("cols", cols)
                    .put("rows", rows),
            )
        }
    }

    override fun close() {
        onState(ConnectionState.Disconnected)
        writeExecutor.execute {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
        readExecutor.shutdownNow()
        writeExecutor.shutdownNow()
    }

    private fun readLoop(connected: Socket) {
        val reader = BufferedReader(InputStreamReader(connected.getInputStream(), StandardCharsets.UTF_8))
        while (!connected.isClosed) {
            val line = reader.readLine() ?: break
            handleFrame(JSONObject(line))
        }
    }

    private fun handleFrame(frame: JSONObject) {
        when (frame.optString("type")) {
            "session_attached" -> {
                sessionId = frame.getString("session_id")
                onState(ConnectionState.Attached)
                onOutput("Attached to ${frame.optString("session_name", "main")}\r\n")
            }
            "scrollback" -> {
                val chunks = frame.optJSONArray("chunks_b64") ?: return
                for (index in 0 until chunks.length()) {
                    val bytes = decodeBase64(chunks.getString(index))
                    onBytes(bytes, true)
                    onOutput(String(bytes, StandardCharsets.UTF_8))
                }
            }
            "output" -> {
                val bytes = decodeBase64(frame.getString("data_b64"))
                onBytes(bytes, false)
                onOutput(String(bytes, StandardCharsets.UTF_8))
            }
            "error" -> onOutput("\r\n[${frame.optString("code")}: ${frame.optString("message")}]\r\n")
        }
    }

    @Synchronized
    private fun sendJson(frame: JSONObject) {
        val currentWriter = writer ?: return
        currentWriter.write(frame.toString())
        currentWriter.write("\n")
        currentWriter.flush()
    }

    private fun decodeBase64Text(encoded: String): String {
        val bytes = decodeBase64(encoded)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun decodeBase64(encoded: String): ByteArray = Base64.decode(encoded, Base64.DEFAULT)
}

enum class ConnectionState {
    Connecting,
    Attached,
    Disconnected,
}
