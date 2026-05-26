package dev.wispshell.app.terminal

interface WispTerminalConnection {
    fun connect()
    fun sendInput(data: String)
    fun sendBytes(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}
