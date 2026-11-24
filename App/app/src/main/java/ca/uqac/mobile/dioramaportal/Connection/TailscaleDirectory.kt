package ca.uqac.mobile.dioramaportal.Connection

import android.content.Context
import androidx.core.content.edit
import ca.uqac.mobile.dioramaportal.Log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Liste les appareils du réseau Tailscale (tailnet) grâce à l'API Tailscale.
 *
 * Une app Android ne peut pas demander la liste des pairs à l'app Tailscale, et mDNS ne
 * traverse pas le VPN : il faut une clé d'API (console Tailscale → Settings → Keys →
 * « Generate access token »), saisie une fois dans l'app et gardée sur l'appareil.
 *
 * Pour chaque appareil, on teste aussi si l'app y est ouverte (port [PeerLink.PORT]).
 */
class TailscaleDirectory(context: Context) {

    data class Device(
        val hostname: String,
        val ip: String,          // adresse Tailscale IPv4 (100.x.y.z)
        val os: String,
        val online: Boolean?,    // connecté à Tailscale (null = inconnu)
        val appOpen: Boolean,    // l'app écoute sur le port 47800
        val isSelf: Boolean,
    )

    sealed interface State {
        data object NoKey : State
        data object Loading : State
        data class Loaded(val devices: List<Device>) : State
        data class Error(val message: String) : State
    }

    // Exclu des sauvegardes Android (voir res/xml/*backup*), la clé ne quitte pas l'appareil.
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    private val _state = MutableStateFlow<State>(if (apiKey() == null) State.NoKey else State.Loaded(emptyList()))
    val state: StateFlow<State> = _state.asStateFlow()

    private fun apiKey(): String? = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun saveKey(key: String) {
        prefs.edit { putString(KEY_API, key.trim()) }
        refresh()
    }

    fun forgetKey() {
        refreshJob?.cancel()
        prefs.edit { remove(KEY_API) }
        _state.value = State.NoKey
    }

    /**
     * Un autre appareil a annoncé sa présence (HELLO / BYE) : on met à jour juste lui,
     * sans rappeler l'API. S'il n'est pas dans la liste (appareil ajouté au tailnet depuis
     * le dernier chargement), on recharge la liste pour le découvrir.
     */
    fun setAppOpen(ip: String, open: Boolean) {
        val s = _state.value as? State.Loaded ?: return
        if (s.devices.none { it.ip == ip }) {
            if (open) refresh()
            return
        }
        _state.update { current ->
            if (current !is State.Loaded) current
            else current.copy(devices = current.devices.map { if (it.ip == ip) it.copy(appOpen = open) else it })
        }
    }

    /** Notre app est-elle à l'écran ? Décide si on annonce HELLO après un chargement. */
    @Volatile private var visible = true

    /**
     * Notre app passe à l'écran (HELLO) ou en arrière-plan (BYE) : on prévient tout de suite
     * les autres appareils de la liste, qui mettent à jour notre état sans recharger.
     */
    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        announce(if (isVisible) PeerLink.HELLO else PeerLink.BYE)
    }

    /** Le VPN de cet appareil est tombé : on ne peut plus joindre personne. */
    fun markAllClosed() {
        _state.update { current ->
            if (current !is State.Loaded) current
            else current.copy(devices = current.devices.map { if (it.isSelf) it else it.copy(appOpen = false) })
        }
    }

    /** Envoie [message] (HELLO ou BYE) à tous les autres appareils de la liste, en parallèle. */
    private fun announce(message: String, inScope: CoroutineScope = scope) {
        val others = (_state.value as? State.Loaded)?.devices?.filterNot { it.isSelf }.orEmpty()
        others.forEach { d -> inScope.launch { sendLine(d.ip, message) } }
    }

    /** @Synchronized : appelé depuis l'UI et depuis le callback VPN (autre thread). */
    @Synchronized
    fun refresh() {
        val key = apiKey() ?: run { _state.value = State.NoKey; return }
        _state.value = State.Loading
        refreshJob?.cancel() // un seul rafraîchissement à la fois
        refreshJob = scope.launch {
            val result = try {
                val myIps = PeerLink.localAddresses().map { it.substringBefore(' ') }.toSet()
                val devices = fetchWithRetry(key)
                    .map { it.copy(isSelf = it.ip in myIps) }
                    .map { d -> async { if (d.isSelf) d else d.copy(appOpen = probe(d.ip)) } }
                    .awaitAll()
                    .sortedWith(compareBy({ it.isSelf }, { !it.appOpen }, { it.online == false }, { it.hostname }))
                AppLog.log("Tailscale : ${devices.size} appareil(s), app ouverte sur ${devices.count { it.appOpen }}")
                State.Loaded(devices)
            } catch (e: CancellationException) {
                throw e // écran fermé : pas une erreur
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                AppLog.log("Tailscale : erreur API : $msg")
                State.Error(msg)
            }
            _state.value = result
            // Liste à jour : si notre app est à l'écran, on prévient les autres.
            if (result is State.Loaded && visible) announce(PeerLink.HELLO)
        }
    }

    /**
     * Fermeture de l'app : on prévient les autres (BYE). L'envoi part d'un scope à part,
     * car [scope] est annulé juste après et couperait les envois en cours.
     */
    fun close() {
        if (visible) announce(PeerLink.BYE, inScope = CoroutineScope(Dispatchers.IO)) // sinon déjà envoyé
        scope.cancel()
    }

    /** Connexion TCP courte : une ligne envoyée, puis fermeture. Les échecs sont ignorés. */
    private fun sendLine(ip: String, line: String) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, PeerLink.PORT), PROBE_TIMEOUT_MS)
                s.getOutputStream().write("$line\n".toByteArray())
            }
        }
    }

    /**
     * Au lancement de l'app, le réseau (DNS, Tailscale qui démarre) n'est souvent pas encore
     * prêt : « Unable to resolve host ». On réessaie quelques fois avant d'afficher une erreur.
     * Une clé refusée (401/403) n'est pas réessayée.
     */
    private suspend fun fetchWithRetry(key: String): List<Device> {
        RETRY_DELAYS_MS.forEachIndexed { attempt, wait ->
            try {
                return fetchDevices(key)
            } catch (e: IOException) {
                AppLog.log("Tailscale : réseau pas prêt (${e.message}), nouvel essai dans ${wait / 1000} s (${attempt + 1}/${RETRY_DELAYS_MS.size})")
                delay(wait)
            }
        }
        return fetchDevices(key) // dernier essai : son erreur est affichée
    }

    private fun fetchDevices(key: String): List<Device> {
        val conn = URL(DEVICES_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $key")
            when (val code = conn.responseCode) {
                200 -> Unit
                401, 403 -> throw IllegalStateException("clé refusée ($code) : expirée ou invalide")
                else -> throw IllegalStateException("réponse HTTP $code de l'API Tailscale")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = json.getJSONArray("devices")
            return (0 until arr.length()).mapNotNull { i ->
                val d = arr.getJSONObject(i)
                val addresses = d.optJSONArray("addresses") ?: return@mapNotNull null
                val ipv4 = (0 until addresses.length()).map { addresses.getString(it) }
                    .firstOrNull { !it.contains(':') } ?: return@mapNotNull null
                Device(
                    hostname = d.optString("hostname").ifBlank { d.optString("name").substringBefore('.') },
                    ip = ipv4,
                    os = d.optString("os"),
                    online = if (d.has("connectedToControl")) d.getBoolean("connectedToControl") else null,
                    appOpen = false,
                    isSelf = false,
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * L'app est-elle à l'écran sur cet appareil ? On envoie [PeerLink.PING] : l'app répond
     * [PeerLink.PONG] si elle est à l'écran, [PeerLink.AWAY] si elle tourne en arrière-plan.
     * Seul PONG compte comme « présent ».
     */
    private fun probe(ip: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, PeerLink.PORT), PROBE_TIMEOUT_MS)
            s.soTimeout = PROBE_TIMEOUT_MS
            s.getOutputStream().write("${PeerLink.PING}\n".toByteArray())
            s.getInputStream().bufferedReader().readLine() == PeerLink.PONG
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val PREFS = "tailscale"
        const val KEY_API = "api_key"
        const val DEVICES_URL = "https://api.tailscale.com/api/v2/tailnet/-/devices"
        const val PROBE_TIMEOUT_MS = 1500
        val RETRY_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L)
    }
}
