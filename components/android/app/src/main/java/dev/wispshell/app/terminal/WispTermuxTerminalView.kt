package dev.wispshell.app.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.wispshell.app.data.RendezvousInfo
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val TAG = "WispTermuxTerminal"

@Composable
fun WispTermuxTerminalView(
    modifier: Modifier = Modifier,
    rendezvous: RendezvousInfo? = null,
    clientPrivateKey: String? = null,
    clientDeviceId: String? = null,
    bindingId: String? = null,
    host: String = "127.0.0.1",
    port: Int = 7777,
    onConnectionState: (ConnectionState) -> Unit = {},
    onConnectionError: (String) -> Unit = {},
) {
    val holder = remember(rendezvous?.irohNodeAddrJson, clientDeviceId, bindingId, host, port) {
        TermuxTerminalHolder(
            host,
            port,
            rendezvous,
            clientPrivateKey,
            clientDeviceId,
            bindingId,
            onConnectionState,
            onConnectionError,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            holder.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalView(context, null).apply {
                setBackgroundColor(Color.BLACK)
                setTextSize(28)
                setTypeface(Typeface.MONOSPACE)
                isFocusable = true
                isFocusableInTouchMode = true
                holder.attachView(this)
            }
        },
    )
}

private class TermuxTerminalHolder(
    private val host: String,
    private val port: Int,
    private val rendezvous: RendezvousInfo?,
    private val clientPrivateKey: String?,
    private val clientDeviceId: String?,
    private val bindingId: String?,
    private val onConnectionState: (ConnectionState) -> Unit,
    private val onConnectionError: (String) -> Unit,
) : TerminalSessionClient, TerminalViewClient {
    private var view: TerminalView? = null
    private var emulator: TerminalEmulator? = null
    private var dummySession: TerminalSession? = null
    private var connection: WispTerminalConnection? = null

    fun attachView(newView: TerminalView) {
        view = newView
        newView.setTerminalViewClient(this)

        val remoteOutput = RemoteTerminalOutput { bytes ->
            connection?.sendBytes(bytes)
        }
        val remoteEmulator = TerminalEmulator(
            remoteOutput,
            80,
            24,
            2000,
            this,
        )
        emulator = remoteEmulator
        dummySession = TerminalSession(
            "/system/bin/sh",
            "/",
            arrayOf("-c", "while true; do sleep 3600; done"),
            emptyArray(),
            2000,
            this,
        )
        newView.attachSession(dummySession)
        newView.mEmulator = remoteEmulator
        newView.requestFocus()

        connection = createConnection(newView, remoteEmulator).also { it.connect() }
    }

    private fun createConnection(
        newView: TerminalView,
        remoteEmulator: TerminalEmulator,
    ): WispTerminalConnection {
        val onBytes: (ByteArray) -> Unit = { bytes ->
            newView.post {
                remoteEmulator.append(bytes, bytes.size)
                newView.onScreenUpdated()
            }
        }
        val onState: (ConnectionState) -> Unit = { state -> onConnectionState(state) }
        val onError: (String) -> Unit = { message ->
            onConnectionError(message)
        }
        val nativeRendezvous = rendezvous
        val nativePrivateKey = clientPrivateKey
        val nativeClientDeviceId = clientDeviceId
        val nativeBindingId = bindingId
        if (
            nativeRendezvous?.irohNodeAddrJson != null &&
            !nativePrivateKey.isNullOrBlank() &&
            !nativeClientDeviceId.isNullOrBlank() &&
            !nativeBindingId.isNullOrBlank()
        ) {
            return NativeP2pTerminalConnection(
                rendezvousJson = nativeRendezvous.toNativeJson(),
                privateKey = nativePrivateKey,
                clientDeviceId = nativeClientDeviceId,
                bindingId = nativeBindingId,
                onBytes = onBytes,
                onState = onState,
                onConnectionError = onError,
            )
        }
        return TerminalConnection(
            host = host,
            port = port,
            onOutput = {},
            onBytes = onBytes,
            onState = onState,
        )
    }

    fun close() {
        connection?.close()
        dummySession?.finishIfRunning()
        connection = null
        dummySession = null
        emulator = null
        view = null
    }

    private fun sendRemote(text: String): Boolean {
        connection?.sendInput(text)
        return true
    }

    private fun showKeyboard() {
        val terminalView = view ?: return
        terminalView.requestFocus()
        terminalView.post {
            val imm =
                terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onTextChanged(changedSession: TerminalSession?) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession?) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession?) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession?) = Unit
    override fun onColorsChanged(session: TerminalSession?) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null

    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(event: MotionEvent?) {
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, event: KeyEvent?, session: TerminalSession?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> sendRemote("\r")
            KeyEvent.KEYCODE_DEL -> sendRemote("\u007f")
            KeyEvent.KEYCODE_TAB -> sendRemote("\t")
            KeyEvent.KEYCODE_ESCAPE -> sendRemote("\u001b")
            KeyEvent.KEYCODE_DPAD_UP -> sendRemote("\u001b[A")
            KeyEvent.KEYCODE_DPAD_DOWN -> sendRemote("\u001b[B")
            KeyEvent.KEYCODE_DPAD_RIGHT -> sendRemote("\u001b[C")
            KeyEvent.KEYCODE_DPAD_LEFT -> sendRemote("\u001b[D")
            else -> sendKeyEventCodePoint(event)
        }
    }

    private fun sendKeyEventCodePoint(event: KeyEvent?): Boolean {
        val codePoint = event?.getUnicodeChar(event.metaState) ?: return false
        if (codePoint <= 0 || codePoint and KeyCharacterMap.COMBINING_ACCENT != 0) {
            return false
        }
        return sendRemote(String(Character.toChars(codePoint)))
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(
        codePoint: Int,
        ctrlDown: Boolean,
        session: TerminalSession?,
    ): Boolean {
        val remoteCodePoint = if (ctrlDown) {
            when (codePoint) {
                in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
                in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
                ' '.code, '2'.code -> 0
                '['.code, '3'.code -> 27
                '\\'.code, '4'.code -> 28
                ']'.code, '5'.code -> 29
                '^'.code, '6'.code -> 30
                '_'.code, '7'.code, '/'.code -> 31
                '8'.code -> 127
                else -> codePoint
            }
        } else {
            codePoint
        }
        return sendRemote(String(Character.toChars(remoteCodePoint)))
    }

    override fun onEmulatorSet() {
        val terminalView = view ?: return
        val remoteEmulator = emulator ?: return
        val sizeSource = dummySession?.emulator ?: return
        remoteEmulator.resize(sizeSource.mColumns, sizeSource.mRows)
        terminalView.mEmulator = remoteEmulator
        connection?.resize(sizeSource.mColumns, sizeSource.mRows)
    }

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: TAG, message.orEmpty())
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: TAG, message.orEmpty())
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: TAG, message.orEmpty())
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: TAG, message.orEmpty())
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: TAG, message.orEmpty())
    }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message.orEmpty(), e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "terminal exception", e)
    }
}

private class RemoteTerminalOutput(
    private val onWrite: (ByteArray) -> Unit,
) : TerminalOutput() {
    override fun write(data: ByteArray, offset: Int, count: Int) {
        onWrite(data.copyOfRange(offset, offset + count))
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onCopyTextToClipboard(text: String?) = Unit
    override fun onPasteTextFromClipboard() = Unit
    override fun onBell() = Unit
    override fun onColorsChanged() = Unit
}
