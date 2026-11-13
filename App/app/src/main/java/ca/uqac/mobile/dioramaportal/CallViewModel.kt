package ca.uqac.mobile.dioramaportal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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

    init {
        link.onConnected = { initiator -> call.start(initiator) }
        link.onSignal = { json -> call.handleSignal(json) }
        link.onDisconnected = {
            call.stop()
            portalMode.value = false
        }
        link.startListening()
        tailscale.refresh()
    }

    /** Appelé seulement quand l'écran est vraiment quitté, pas lors d'une rotation. */
    override fun onCleared() {
        link.close()
        call.release()
        tailscale.close()
    }
}
