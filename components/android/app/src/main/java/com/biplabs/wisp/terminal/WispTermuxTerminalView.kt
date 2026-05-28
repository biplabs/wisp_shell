package com.biplabs.wisp.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.biplabs.wisp.data.RendezvousInfo
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
    sessionName: String = "main",
    active: Boolean = true,
    reconnectNonce: Int = 0,
    rendezvous: RendezvousInfo? = null,
    clientPrivateKey: String? = null,
    clientDeviceId: String? = null,
    bindingId: String? = null,
    host: String = "127.0.0.1",
    port: Int = 7777,
    onConnectionState: (ConnectionState) -> Unit = {},
    onConnectionError: (String) -> Unit = {},
    onTitleChanged: (String) -> Unit = {},
    onSendInputReady: (((String) -> Unit)?) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember(sessionName, reconnectNonce, rendezvous?.irohNodeAddrJson, clientDeviceId, bindingId, host, port) {
        TermuxTerminalHolder(
            host,
            port,
            sessionName,
            rendezvous,
            clientPrivateKey,
            clientDeviceId,
            bindingId,
            onConnectionState,
            onConnectionError,
            onTitleChanged,
        )
    }
    DisposableEffect(lifecycleOwner, holder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                holder.reconnectIfDisconnected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(holder) {
        onSendInputReady { input -> holder.sendInput(input) }
        onDispose {
            onSendInputReady(null)
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
        update = { view ->
            view.alpha = if (active) 1f else 0f
            view.isEnabled = active
            if (active) {
                view.requestFocus()
            }
        },
    )
}

private class TermuxTerminalHolder(
    private val host: String,
    private val port: Int,
    private val sessionName: String,
    private val rendezvous: RendezvousInfo?,
    private val clientPrivateKey: String?,
    private val clientDeviceId: String?,
    private val bindingId: String?,
    private val onConnectionState: (ConnectionState) -> Unit,
    private val onConnectionError: (String) -> Unit,
    private val onTitleChanged: (String) -> Unit,
) : TerminalSessionClient, TerminalViewClient {
    private var view: TerminalView? = null
    private var emulator: TerminalEmulator? = null
    private var dummySession: TerminalSession? = null
    private var connection: WispTerminalConnection? = null
    private var layoutListener: View.OnLayoutChangeListener? = null
    @Volatile private var suppressRemoteWrites = false
    @Volatile private var connectionState = ConnectionState.Connecting

    fun attachView(newView: TerminalView) {
        view?.let { oldView ->
            layoutListener?.let(oldView::removeOnLayoutChangeListener)
        }
        view = newView
        newView.setTerminalViewClient(this)

        val remoteOutput = RemoteTerminalOutput(
            onWrite = { bytes ->
                if (suppressRemoteWrites) return@RemoteTerminalOutput
                connection?.sendBytes(bytes)
            },
            onTitle = { title -> updateTitle(title) },
        )
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

        startConnection(newView, remoteEmulator)
        layoutListener = View.OnLayoutChangeListener { changedView, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                (changedView as? TerminalView)?.let(::syncSizeFromView)
            }
        }.also(newView::addOnLayoutChangeListener)
        newView.post { syncSizeFromView(newView) }
    }

    @Synchronized
    fun reconnectIfDisconnected() {
        if (connectionState != ConnectionState.Disconnected) return
        val terminalView = view ?: return
        val remoteEmulator = emulator ?: return
        startConnection(terminalView, remoteEmulator)
    }

    @Synchronized
    private fun startConnection(
        terminalView: TerminalView,
        remoteEmulator: TerminalEmulator,
    ) {
        connection?.close()
        connectionState = ConnectionState.Connecting
        val nextConnection = createConnection(terminalView, remoteEmulator)
        connection = nextConnection
        val sizeSource = dummySession?.emulator
        if (sizeSource != null) {
            nextConnection.resize(sizeSource.mColumns, sizeSource.mRows)
        }
        nextConnection.connect()
    }

    private fun createConnection(
        newView: TerminalView,
        remoteEmulator: TerminalEmulator,
    ): WispTerminalConnection {
        val onBytes: (ByteArray, Boolean) -> Unit = { bytes, fromScrollback ->
            inferTitle(bytes)?.let { updateTitle(it) }
            newView.post {
                if (fromScrollback) {
                    suppressRemoteWrites = true
                }
                try {
                    remoteEmulator.append(bytes, bytes.size)
                    newView.onScreenUpdated()
                } finally {
                    if (fromScrollback) {
                        suppressRemoteWrites = false
                    }
                }
            }
        }
        val onState: (ConnectionState) -> Unit = { state ->
            connectionState = state
            if (state == ConnectionState.Attached) {
                resizeConnectionToView()
            }
            onConnectionState(state)
        }
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
                sessionName = sessionName,
                onBytes = onBytes,
                onState = onState,
                onConnectionError = onError,
            )
        }
        return TerminalConnection(
            host = host,
            port = port,
            sessionName = sessionName,
            onOutput = {},
            onBytes = onBytes,
            onState = onState,
        )
    }

    fun close() {
        view?.let { terminalView ->
            layoutListener?.let(terminalView::removeOnLayoutChangeListener)
        }
        layoutListener = null
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

    fun sendInput(text: String): Boolean {
        showKeyboard()
        return sendRemote(text)
    }

    private fun showKeyboard() {
        val terminalView = view ?: return
        terminalView.requestFocus()
        terminalView.post {
            val imm =
                terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
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
    override fun shouldEnforceCharBasedInput(): Boolean = false
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
        resizeConnectionToView()
    }

    private fun syncSizeFromView(terminalView: TerminalView) {
        if (terminalView.width == 0 || terminalView.height == 0) return
        terminalView.updateSize()
        emulator?.let { terminalView.mEmulator = it }
        resizeConnectionToView()
    }

    private fun resizeConnectionToView() {
        val sizeSource = dummySession?.emulator ?: return
        connection?.resize(sizeSource.mColumns, sizeSource.mRows)
    }

    private fun updateTitle(title: String?) {
        val normalized = title?.trim()?.takeIf { it.isNotEmpty() } ?: return
        onTitleChanged(folderName(normalized))
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
    private val onTitle: (String?) -> Unit,
) : TerminalOutput() {
    override fun write(data: ByteArray, offset: Int, count: Int) {
        onWrite(data.copyOfRange(offset, offset + count))
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        onTitle(newTitle)
    }
    override fun onCopyTextToClipboard(text: String?) = Unit
    override fun onPasteTextFromClipboard() = Unit
    override fun onBell() = Unit
    override fun onColorsChanged() = Unit
}

private fun inferTitle(bytes: ByteArray): String? {
    val text = bytes.toString(Charsets.UTF_8)
        .replace(Regex("\\u001b\\[[0-?]*[ -/]*[@-~]"), "")
        .replace(Regex("\\u001b\\][^\\u0007]*(\\u0007|\\u001b\\\\)"), "")
    val directory = Regex("""directory:\s+([^\r\n ]+)""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
    if (!directory.isNullOrBlank()) {
        return folderName(directory)
    }
    val promptPath = Regex("""(?:^|[\s.])(~?/[A-Za-z0-9._~/-]+)""")
        .findAll(text)
        .map { it.groupValues[1] }
        .lastOrNull { it.contains('/') }
    return promptPath?.let(::folderName)
}

private fun folderName(pathOrTitle: String): String {
    val cleaned = pathOrTitle
        .trim()
        .trimEnd('/', ':')
    return cleaned.substringAfterLast('/').ifBlank { cleaned }
}
