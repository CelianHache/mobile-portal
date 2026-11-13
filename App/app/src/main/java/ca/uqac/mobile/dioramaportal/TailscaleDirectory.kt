package ca.uqac.mobile.dioramaportal

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
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

    private val _state = MutableStateFlow<State>(if (apiKey() == null) State.NoKey else State.Loaded(emptyList()))
    val state: StateFlow<State> = _state.asStateFlow()

    private fun apiKey(): String? = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun saveKey(key: String) {
        prefs.edit().putString(KEY_API, key.trim()).apply()
        refresh()
    }

    fun forgetKey() {
        prefs.edit().remove(KEY_API).apply()
        _state.value = State.NoKey
    }

    fun refresh() {
        val key = apiKey() ?: run { _state.value = State.NoKey; return }
        _state.value = State.Loading
        scope.launch {
            _state.value = try {
                val myIps = PeerLink.localAddresses().map { it.substringBefore(' ') }.toSet()
                val devices = fetchDevices(key)
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
        }
    }

    fun close() = scope.cancel()

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
     * L'app est-elle ouverte sur cet appareil ? On envoie [PeerLink.PING] : l'app répond
     * sans ouvrir de session. Si elle est déjà en appel, elle ferme la connexion, mais le
     * port a répondu : elle est bien ouverte.
     */
    private fun probe(ip: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, PeerLink.PORT), PROBE_TIMEOUT_MS)
            s.soTimeout = PROBE_TIMEOUT_MS
            s.getOutputStream().write("${PeerLink.PING}\n".toByteArray())
            runCatching { s.getInputStream().bufferedReader().readLine() }
            true
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val PREFS = "tailscale"
        const val KEY_API = "api_key"
        const val DEVICES_URL = "https://api.tailscale.com/api/v2/tailnet/-/devices"
        const val PROBE_TIMEOUT_MS = 1500
    }
}
