package dev.wispshell.app.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WispTerminalWebView(
    modifier: Modifier = Modifier,
    host: String = "127.0.0.1",
    port: Int = 7777,
) {
    val bridge = remember { TerminalBridge() }
    DisposableEffect(Unit) {
        onDispose {
            bridge.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                isFocusable = true
                isFocusableInTouchMode = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        bridge.attach(
                            TerminalConnection(
                                host = host,
                                port = port,
                                onOutput = { output ->
                                    view.post {
                                        view.evaluateJavascript(
                                            "window.writeTerminal && window.writeTerminal(${JSONObject.quote(output)});",
                                            null,
                                        )
                                    }
                                },
                            ),
                        )
                    }
                }
                setOnTouchListener { view, _ ->
                    view.requestFocus()
                    val imm =
                        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                    evaluateJavascript(
                        "window.focusTerminalInput && window.focusTerminalInput();",
                        null,
                    )
                    false
                }
                addJavascriptInterface(bridge, "WispTerminal")
                loadUrl("file:///android_asset/terminal/index.html")
            }
        },
    )
}

class TerminalBridge {
    private var connection: TerminalConnection? = null

    fun attach(newConnection: TerminalConnection) {
        connection?.close()
        connection = newConnection
        newConnection.connect()
    }

    @JavascriptInterface
    fun sendInput(data: String) {
        connection?.sendInput(data)
    }

    fun close() {
        connection?.close()
        connection = null
    }
}
