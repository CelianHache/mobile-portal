package ca.uqac.mobile.dioramaportal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Lien minimal entre deux appareils : un socket TCP sur un port fixe,
 * un message texte par ligne. Chaque appareil écoute ; celui qui saisit
 * l'adresse de l'autre (IP Tailscale 100.x.y.z ou nom MagicDNS) se connecte.
 */
class PeerLink {

    enum class Status { IDLE, LISTENING, CONNECTING, CONNECTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readJob: Job? = null

    /** Ouvre le port d'écoute et accepte les connexions entrantes. */
    fun startListening() {
        if (server != null) return
        scope.launch {
            try {
                val ss = ServerSocket(PORT).also { server = it }
                if (socket == null) _status.value = Status.LISTENING
                log("En écoute sur le port $PORT")
                while (!ss.isClosed) {
                    val incoming = ss.accept()
                    if (socket != null) {
                        // Déjà en session : on refuse.
                        incoming.close()
                        log("Connexion refusée de ${incoming.inetAddress.hostAddress} (déjà connecté)")
                        continue
                    }
                    log("Connexion entrante de ${incoming.inetAddress.hostAddress}")
                    attach(incoming)
                }
            } catch (e: Exception) {
                if (server?.isClosed != true) log("Erreur serveur : ${e.message}")
            }
        }
    }

    /** Se connecte à l'autre appareil (IP ou nom d'hôte). */
    fun connect(host: String) {
        if (socket != null) return
        _status.value = Status.CONNECTING
        log("Connexion à $host:$PORT…")
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host.trim(), PORT), CONNECT_TIMEOUT_MS)
                attach(s)
                send("hello from ${android.os.Build.MODEL}")
            } catch (e: Exception) {
                log("Échec : ${e.message}")
                _status.value = if (server != null) Status.LISTENING else Status.IDLE
            }
        }
    }

    fun send(text: String) {
        val w = writer ?: return
        scope.launch {
            try {
                synchronized(w) {
                    w.write(text.replace('\n', ' '))
                    w.newLine()
                    w.flush()
                }
                log("→ $text")
            } catch (e: Exception) {
                log("Envoi impossible : ${e.message}")
                disconnect()
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        runCatching { socket?.close() }
        if (socket != null) log("Déconnecté")
        socket = null
        writer = null
        _status.value = if (server != null) Status.LISTENING else Status.IDLE
    }

    fun close() {
        disconnect()
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    private fun attach(s: Socket) {
        s.tcpNoDelay = true
        socket = s
        writer = s.getOutputStream().bufferedWriter()
        _status.value = Status.CONNECTED
        log("Connecté à ${s.inetAddress.hostAddress}")
        val reader: BufferedReader = s.getInputStream().bufferedReader()
        readJob = scope.launch {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    log("← $line")
                }
            } catch (_: Exception) {
            }
            if (socket === s) disconnect()
        }
    }

    private fun log(msg: String) {
        _log.update { (it + msg).takeLast(200) }
    }

    companion object {
        const val PORT = 47800
        private const val CONNECT_TIMEOUT_MS = 5000

        /** Adresses IPv4 locales (l'IP Tailscale commence par 100.). */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .map { "${it.hostAddress} (${nif.name})" }
                }
        }.getOrDefault(emptyList())
    }
}
