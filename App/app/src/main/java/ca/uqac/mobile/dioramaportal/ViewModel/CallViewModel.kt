package ca.uqac.mobile.dioramaportal.ViewModel

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import ca.uqac.mobile.dioramaportal.Log.AppLog
import ca.uqac.mobile.dioramaportal.Connection.PeerLink
import ca.uqac.mobile.dioramaportal.Connection.TailscaleDirectory
import ca.uqac.mobile.dioramaportal.VideoCall
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Garde le socket et l'appel vidéo en vie pendant les changements de configuration
 * (rotation de l'écran) : l'activité est recréée, pas ce ViewModel.
 */
class CallViewModel(app: Application) : AndroidViewModel(app) {

    // En premier : le journal doit être prêt avant que les autres objets écrivent dedans.
    @Suppress("unused")
    private val logReady = AppLog.init(app)

    val link = PeerLink()
    val call = VideoCall(app, sendSignal = link::send, log = AppLog::log)
    val tailscale = TailscaleDirectory(app)

    /** Mode portail : vidéo distante seule, plein écran, comme dans le boîtier diorama. */
    val portalMode = MutableStateFlow(false)

    private val connectivityManager = app.getSystemService(ConnectivityManager::class.java)

    /**
     * Écoute le VPN (Tailscale) de cet appareil :
     * - il apparaît (au lancement s'il est déjà là, ou quand on l'active) → on charge la liste,
     *   ce qui envoie HELLO aux autres ;
     * - il disparaît → plus personne n'est joignable.
     * Appelé sur un thread système, pas sur le thread principal.
     */
    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppLog.log("VPN actif : rechargement de la liste Tailscale")
            tailscale.refresh()
        }

        override fun onLost(network: Network) {
            AppLog.log("VPN coupé : appareils Tailscale injoignables")
            tailscale.markAllClosed()
        }
    }

    init {
        link.onConnected = { initiator -> call.start(initiator) }
        link.onSignal = { json -> call.handleSignal(json) }
        link.onDisconnected = {
            call.stop()
            portalMode.value = false
        }
        // HELLO / BYE reçus d'un autre appareil : mise à jour immédiate de la liste.
        link.onPresence = { ip, open -> tailscale.setAppOpen(ip, open) }
        link.startListening()

        // Si le VPN est déjà actif, onAvailable est appelé tout de suite : c'est lui qui fait
        // le premier chargement. Sinon on charge quand même (l'API marche aussi sans VPN).
        val vpnRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(vpnRequest, vpnCallback)
        tailscale.refresh()
    }

    /**
     * L'app passe à l'écran ou en arrière-plan (appelé par MainActivity.onStart / onStop).
     * Les autres appareils ne nous voient que quand l'app est à l'écran :
     * - à l'écran : HELLO envoyé, et on répond PONG aux PING ;
     * - en arrière-plan (accueil, autre app, écran éteint, apps récentes avant un balayage) :
     *   BYE envoyé, et on répond AWAY aux PING.
     */
    fun setAppVisible(isVisible: Boolean) {
        if (link.visible == isVisible) return
        link.visible = isVisible
        AppLog.log(if (isVisible) "App à l'écran : visible des autres appareils" else "App en arrière-plan : invisible des autres appareils")
        tailscale.setVisible(isVisible)
    }

    /** Appelé seulement quand l'écran est vraiment quitté, pas lors d'une rotation. */
    override fun onCleared() {
        runCatching { connectivityManager.unregisterNetworkCallback(vpnCallback) }
        link.close()
        call.release()
        tailscale.close() // envoie BYE aux autres
    }
}