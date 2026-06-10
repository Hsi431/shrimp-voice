package com.shrimp.voice.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Connection states emitted by the WebSocket client.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * OkHttp-based WebSocket client for the shrimp-server.
 *
 * - Connects to wss://<host>/audio
 * - Accepts self-signed certificates (dev mode)
 * - Sends: byte[] PCM 16kHz int16 binary frames
 * - Receives: JSON text messages (log) or binary WAV (for TTS playback)
 * - Auto-reconnect with exponential backoff
 */
class ShrimpWebSocketClient(
    private val host: String,
    private val authToken: String = "",
    private val onConnectionStateChange: (ConnectionState) -> Unit = {},
    private val onJsonMessage: (ShrimpProtocol.MessageFrame) -> Unit = {},
    private val onTtsAudio: (ByteArray) -> Unit = {},
    private val onError: ((String) -> Unit)? = null
) {
    private var webSocket: WebSocket? = null
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var reconnectAttempt = 0
    private var shouldReconnect = false
    var lastError: String? = null
        private set

    private val client: OkHttpClient by lazy {
        createClient()
    }

    private fun createClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(
                SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                }.socketFactory,
                trustManager
            )
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for streaming
            .build()
    }

    fun connect() {
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect()
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        setState(ConnectionState.DISCONNECTED)
    }

    fun sendAudio(data: ByteArray): Boolean {
        return webSocket?.send(ByteString.of(*data)) ?: false
    }

    fun sendText(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }

    private fun doConnect() {
        if (connectionState == ConnectionState.CONNECTED) return
        setState(ConnectionState.CONNECTING)

        val wsUrl = try {
            buildWebSocketUrl(host)
        } catch (e: IllegalArgumentException) {
            fail("Invalid server URL '${host.trim()}': ${e.message}", e)
            scheduleReconnect()
            return
        }

        Timber.d("Opening WebSocket: $wsUrl")
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("WebSocket connected to $wsUrl")
                reconnectAttempt = 0
                setState(ConnectionState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Timber.d("WS text: $text")
                val frame = ShrimpProtocol.parseTextFrame(text)
                if (frame != null) {
                    onJsonMessage(frame)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (ShrimpProtocol.isWavData(data)) {
                    Timber.d("WS binary: WAV audio (%d bytes)", data.size)
                    onTtsAudio(data)
                } else {
                    Timber.d("WS binary: %d bytes (non-WAV)", data.size)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("WebSocket closed: $code $reason")
                setState(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = buildFailureMessage(t, response)
                fail(msg, t)
                setState(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }
        })
    }

    private fun buildWebSocketUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "URL is empty" }

        val withScheme = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) ->
                "wss://" + trimmed.removePrefixIgnoreCase("https://")
            trimmed.startsWith("http://", ignoreCase = true) ->
                "ws://" + trimmed.removePrefixIgnoreCase("http://")
            else -> "wss://$trimmed"
        }

        val withPath = if (withScheme.substringAfter("://").contains("/")) {
            withScheme
        } else {
            "$withScheme/audio"
        }

        if (authToken.isBlank()) return withPath
        val sep = if (withPath.contains('?')) "&" else "?"
        return "$withPath${sep}token=${URLEncoder.encode(authToken, "UTF-8")}"
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String {
        return if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
    }

    private fun buildFailureMessage(t: Throwable, response: Response?): String {
        val httpMessage = response?.let { "HTTP ${it.code}: ${it.message}" }
        val exceptionMessage = "${t::class.java.simpleName}: ${t.message ?: t.toString()}"
        return listOfNotNull(httpMessage, exceptionMessage).joinToString(" / ")
    }

    private fun fail(message: String, throwable: Throwable) {
        lastError = message
        onError?.invoke(message)
        Timber.e(throwable, "WebSocket failure: $message")
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        val delay = reconnectDelaySeconds(reconnectAttempt)
        Timber.d("Reconnecting in ${delay}s (attempt ${reconnectAttempt + 1})")

        Thread {
            try {
                Thread.sleep(delay * 1000L)
                if (!shouldReconnect) return@Thread
                reconnectAttempt++
                doConnect()
            } catch (e: InterruptedException) {
                // cancelled
            }
        }.start()
    }

    private fun reconnectDelaySeconds(attempt: Int): Long {
        val delays = listOf(1L, 2L, 4L, 8L, 16L, 30L)
        return delays.getOrElse(attempt) { 30L }
    }

    private fun setState(state: ConnectionState) {
        if (state != connectionState) {
            connectionState = state
            onConnectionStateChange(state)
        }
    }
}
