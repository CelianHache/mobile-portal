package ca.uqac.mobile.dioramaportal

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import ca.uqac.mobile.dioramaportal.Connection.PeerLink
import ca.uqac.mobile.dioramaportal.Screen.CallScreen
import ca.uqac.mobile.dioramaportal.Screen.ConnectScreen
import ca.uqac.mobile.dioramaportal.Screen.PortalScreen
import ca.uqac.mobile.dioramaportal.ViewModel.CallViewModel
import ca.uqac.mobile.dioramaportal.ui.theme.DioramaPortalTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Survit à la rotation : le socket et l'appel ne sont pas recréés.
    private val vm: CallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestPermissions()
        lifecycleScope.launch { vm.portalMode.collect { applyPortalMode(it) } }
        setContent {
            DioramaPortalTheme {
                val status by vm.link.status.collectAsState()
                val portal by vm.portalMode.collectAsState()
                when {
                    portal -> PortalScreen(vm.call, onExit = { vm.portalMode.value = false })
                    else -> Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        if (status == PeerLink.Status.CONNECTED) {
                            CallScreen(vm, Modifier.padding(innerPadding))
                        } else {
                            ConnectScreen(vm, Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }

    /**
     * Mode portail : barres système masquées, écran toujours allumé, orientation verrouillée
     * (le téléphone est fixe dans son boîtier).
     */
    private fun applyPortalMode(on: Boolean) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** De retour sur l'app : la caméra repart et l'autre appareil revoit l'image. */
    override fun onStart() {
        super.onStart()
        vm.call.resumeCamera()
    }

    /**
     * L'app passe en arrière-plan (accueil, autre app, écran éteint) : on arrête d'envoyer
     * des images, mais la connexion reste ouverte.
     * Pas pendant une rotation : onStop est aussi appelé quand l'activité est recréée.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.call.pauseCamera()
    }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == false) {
                Toast.makeText(this, "Sans la caméra, l'autre appareil ne recevra pas d'image", Toast.LENGTH_LONG).show()
            }
            if (result[LOCAL_NETWORK] == false) {
                Toast.makeText(
                    this,
                    "Sans l'accès au réseau local, seule la connexion via Tailscale fonctionne",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    /** Caméra + réseau local (Android 17 bloque le trafic vers le réseau local sans cette permission). */
    private fun requestPermissions() {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 37) add(LOCAL_NETWORK)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionRequest.launch(wanted.toTypedArray())
    }

    private companion object {
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
