package ca.uqac.mobile.dioramaportal.Connection

import android.os.Build
import ca.uqac.mobile.dioramaportal.Log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
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
 * l'adresse de l'autre (IP Tailscale 100.x.y.z ou nom MagicDNS) se connecte
 * et devient l'initiateur.
 *
 * Les lignes JSON (commençant par '{') sont des messages de signalisation
 * WebRTC, transmises à [onSignal]. Les autres sont du texte affiché dans le journal.
 */
class PeerLink {

    enum class Status { IDLE, LISTENING, CONNECTING, CONNECTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Un seul envoi à la fois, dans l'ordre : l'offre doit partir avant les candidats ICE. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Adresse de l'appareil avec lequel on est en appel (null hors appel). */
    private val _peer = MutableStateFlow<String?>(null)
    val peer: StateFlow<String?> = _peer.asStateFlow()

    /** Appelé quand la session TCP s'ouvre. `initiator` = c'est nous qui nous sommes connectés. */
    var onConnected: ((initiator: Boolean) -> Unit)? = null
    /** Appelé pour chaque message de signalisation JSON reçu. */
    var onSignal: ((String) -> Unit)? = null
    /** Appelé quand la session TCP se ferme. */
    var onDisconnected: (() -> Unit)? = null

    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readJob: Job? = null

    /** Ouvre le port d'écoute et accepte les connexions entrantes. */
    fun startListening() {
        if (server != null) return
        scope.launch {
            val ss = bindWithRetry() ?: return@launch
            server = ss
            if (socket == null) _status.value = Status.LISTENING
            log("En écoute sur le port $PORT")
            try {
                while (!ss.isClosed) {
                    val incoming = ss.accept()
                    scope.launch { handleIncoming(incoming) }
                }
            } catch (e: Exception) {
                if (!closed) log("Erreur serveur : ${e.message}")
            }
        }
    }

    /**
     * Le port peut être encore tenu un instant par l'instance précédente (activité relancée
     * avant que l'ancienne soit fermée) : on réessaie quelques secondes au lieu d'abandonner.
     */
    private suspend fun bindWithRetry(): ServerSocket? {
        repeat(BIND_ATTEMPTS) { attempt ->
            if (closed) return null
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
            } catch (e: Exception) {
                if (attempt == BIND_ATTEMPTS - 1) log("Erreur serveur : port $PORT indisponible (${e.message})")
                delay(BIND_RETRY_MS)
            }
        }
        return null
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
                attach(s, initiator = true)
                send("hello from ${Build.MODEL}")
            } catch (e: Exception) {
                log("Échec : ${e.message}")
                _status.value = if (server != null) Status.LISTENING else Status.IDLE
            }
        }
    }

    fun send(text: String) {
        val w = writer ?: return
        scope.launch(writeDispatcher) {
            try {
                w.write(text.replace('\n', ' '))
                w.newLine()
                w.flush()
                if (!text.startsWith("{")) log("→ $text")
            } catch (e: Exception) {
                log("Envoi impossible : ${e.message}")
                disconnect()
            }
        }
    }

    /** Ajoute une ligne au journal affiché à l'écran. */
    fun info(msg: String) = AppLog.log(msg)

    fun disconnect() {
        val s = socket ?: return
        socket = null
        _peer.value = null
        readJob?.cancel()
        readJob = null
        runCatching { s.close() }
        writer = null
        log("Déconnecté")
        _status.value = if (server != null) Status.LISTENING else Status.IDLE
        onDisconnected?.invoke()
    }

    fun close() {
        closed = true
        disconnect()
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    /**
     * Connexion entrante : on lit d'abord une ligne. Un [PING] (test de présence envoyé par la
     * liste Tailscale) reçoit [PONG] et la connexion est fermée, sans ouvrir de session.
     */
    private fun handleIncoming(s: Socket) {
        val from = s.inetAddress.hostAddress
        val reader = s.getInputStream().bufferedReader()
        val first = try {
            s.soTimeout = FIRST_LINE_TIMEOUT_MS
            reader.readLine().also { s.soTimeout = 0 }
        } catch (_: Exception) {
            null
        }
        if (first == null || first == PING) {
            if (first == PING) runCatching { s.getOutputStream().write("$PONG\n".toByteArray()) }
            runCatching { s.close() }
            return
        }
        synchronized(this) {
            if (socket != null) {
                // Déjà en session : on refuse.
                runCatching { s.close() }
                log("Connexion refusée de $from (déjà connecté)")
                return
            }
            log("Connexion entrante de $from")
            attach(s, initiator = false, reader = reader, firstLine = first)
        }
    }

    private fun attach(
        s: Socket,
        initiator: Boolean,
        reader: BufferedReader = s.getInputStream().bufferedReader(),
        firstLine: String? = null,
    ) {
        s.tcpNoDelay = true
        socket = s
        writer = s.getOutputStream().bufferedWriter()
        AppLog.newCall() // le journal de l'appel commence ici
        _peer.value = s.inetAddress.hostAddress
        _status.value = Status.CONNECTED
        log("Connecté à ${s.inetAddress.hostAddress}" + if (initiator) " (initiateur)" else "")
        // Avant de lire : l'appel vidéo doit exister quand l'offre arrive.
        onConnected?.invoke(initiator)
        readJob = scope.launch {
            try {
                firstLine?.let { handleLine(it) }
                while (true) {
                    val line = reader.readLine() ?: break
                    handleLine(line)
                }
            } catch (_: Exception) {
            }
            if (socket === s) disconnect()
        }
    }

    private fun handleLine(line: String) {
        if (line.startsWith("{")) onSignal?.invoke(line) else log("← $line")
    }

    private fun log(msg: String) {
        AppLog.log(msg)
    }

    companion object {
        const val PORT = 47800
        /** Test de présence : l'app répond [PONG] sans ouvrir de session. */
        const val PING = "PING"
        const val PONG = "PONG"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val FIRST_LINE_TIMEOUT_MS = 5000
        private const val BIND_ATTEMPTS = 20
        private const val BIND_RETRY_MS = 500L

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
