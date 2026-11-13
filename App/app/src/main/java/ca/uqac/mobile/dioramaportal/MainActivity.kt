package ca.uqac.mobile.dioramaportal

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import ca.uqac.mobile.dioramaportal.ui.theme.DioramaPortalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {

    // Survit à la rotation : le socket et l'appel ne sont pas recréés.
    private val vm: CallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestPermissions()
        lifecycleScope.launch { vm.windowMode.collect { applyWindowMode(it) } }
        setContent {
            DioramaPortalTheme {
                val windowMode by vm.windowMode.collectAsState()
                if (windowMode) {
                    WindowScreen(vm.call, onExit = { vm.windowMode.value = false })
                } else {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        ConnectionScreen(vm, Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }

    /**
     * Mode fenêtre : barres système masquées, écran toujours allumé, orientation verrouillée
     * (le téléphone est fixe dans son boîtier).
     */
    private fun applyWindowMode(on: Boolean) {
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

/**
 * Mode fenêtre du diorama : seulement la vidéo de l'autre lieu, sur fond noir, ratio conservé.
 * Appui long ou bouton retour pour revenir à l'écran d'appel.
 */
@Composable
fun WindowScreen(call: VideoCall, onExit: () -> Unit) {
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(3000)
        showHint = false
    }
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        VideoView(call, call.remoteSink, mirror = false)
        // Calque transparent au-dessus de la vidéo pour capter l'appui long.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onLongPress = { onExit() }) })
        if (showHint) {
            Text(
                "Appui long pour quitter le mode fenêtre",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun ConnectionScreen(vm: CallViewModel, modifier: Modifier = Modifier) {
    val link = vm.link
    val status by link.status.collectAsState()
    val connected = status == PeerLink.Status.CONNECTED
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Ratio largeur/hauteur du flux reçu (connu à la première image). Gardé à la rotation.
    var remoteRatio by rememberSaveable { mutableStateOf<Float?>(null) }

    if (!connected) {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("État : $status")
            ConnectForm(link, status)
            TailscaleSection(vm.tailscale, enabled = status != PeerLink.Status.CONNECTING) { ip -> link.connect(ip) }
            LogList(link, Modifier.weight(1f).fillMaxWidth())
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("État : $status")
                CallControls(link, onWindowMode = { vm.windowMode.value = true })
                LogList(link, Modifier.weight(1f).fillMaxWidth())
            }
        }
        val onRatio = { r: Float -> remoteRatio = r }

        if (landscape) {
            // Vidéo à gauche : toute la hauteur, largeur dictée par son ratio.
            // Le panneau prend toute la largeur restante (au moins MIN_PANEL).
            val maxVideoWidth = maxWidth - MIN_PANEL
            val videoWidth = remoteRatio?.let { minOf(maxHeight * it, maxVideoWidth) } ?: maxVideoWidth
            Row(Modifier.fillMaxSize()) {
                VideoArea(call = vm.call, Modifier.width(videoWidth).fillMaxHeight(), onRatio)
                panel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Vidéo en haut : toute la largeur, hauteur dictée par son ratio.
            // Le panneau prend toute la hauteur restante (au moins MIN_PANEL).
            val maxVideoHeight = maxHeight - MIN_PANEL
            val videoHeight = remoteRatio?.let { minOf(maxWidth / it, maxVideoHeight) } ?: maxVideoHeight
            Column(Modifier.fillMaxSize()) {
                VideoArea(call = vm.call, Modifier.fillMaxWidth().height(videoHeight), onRatio)
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Place minimale laissée au panneau de messages à côté (ou sous) la vidéo. */
private val MIN_PANEL = 240.dp

@Composable
private fun ConnectForm(link: PeerLink, status: PeerLink.Status) {
    var host by rememberSaveable { mutableStateOf("") }
    val addresses = remember { PeerLink.localAddresses() }
    Text("Mes adresses (port ${PeerLink.PORT}) :")
    addresses.forEach { Text("  $it", fontFamily = FontFamily.Monospace) }
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("IP Tailscale ou nom MagicDNS du pair") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { link.connect(host) },
        enabled = host.isNotBlank() && status != PeerLink.Status.CONNECTING
    ) { Text("Se connecter") }
}

/** Appareils du tailnet, récupérés avec l'API Tailscale : un appui pour appeler. */
@Composable
private fun TailscaleSection(ts: TailscaleDirectory, enabled: Boolean, onConnect: (String) -> Unit) {
    val state by ts.state.collectAsState()
    Text("Appareils Tailscale", style = MaterialTheme.typography.titleMedium)

    when (val s = state) {
        TailscaleDirectory.State.NoKey -> {
            var key by rememberSaveable { mutableStateOf("") }
            Text(
                "Clé d'API : console Tailscale → Settings → Keys → Generate access token",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("tskey-api-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { ts.saveKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Enregistrer") }
            }
        }

        TailscaleDirectory.State.Loading -> Text("Recherche des appareils…")

        is TailscaleDirectory.State.Error -> {
            Text("Erreur : ${s.message}", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ts.refresh() }) { Text("Réessayer") }
                OutlinedButton(onClick = { ts.forgetKey() }) { Text("Changer la clé") }
            }
        }

        is TailscaleDirectory.State.Loaded -> {
            if (s.devices.isEmpty()) Text("Aucun appareil.")
            LazyColumn(Modifier.heightIn(max = 240.dp).fillMaxWidth()) {
                items(s.devices) { d -> DeviceRow(d, enabled) { onConnect(d.ip) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ts.refresh() }) { Text("Actualiser") }
                OutlinedButton(onClick = { ts.forgetKey() }) { Text("Changer la clé") }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: TailscaleDirectory.Device, enabled: Boolean, onClick: () -> Unit) {
    val (dot, detail) = when {
        d.isSelf -> "◦" to "cet appareil"
        d.appOpen -> "●" to "app ouverte · appuyer pour appeler"
        d.online == false -> "○" to "hors ligne"
        else -> "○" to "app fermée"
    }
    val color = if (d.appOpen && !d.isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !d.isSelf, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text("$dot ${d.hostname}  (${d.os})", color = color)
        Text("   ${d.ip} · $detail", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CallControls(link: PeerLink, onWindowMode: () -> Unit) {
    var message by rememberSaveable { mutableStateOf("") }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Message") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { link.send(message); message = "" }) { Text("Envoyer") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onWindowMode) { Text("Mode fenêtre") }
        OutlinedButton(onClick = { link.disconnect() }) { Text("Déconnecter") }
    }
}

@Composable
private fun LogList(link: PeerLink, modifier: Modifier) {
    val log by link.log.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }
    LazyColumn(state = listState, modifier = modifier) {
        items(log) { Text(it, fontFamily = FontFamily.Monospace) }
    }
}

/** Vidéo de l'autre appareil en grand, aperçu de notre caméra en coin. */
@Composable
private fun VideoArea(call: VideoCall, modifier: Modifier, onRemoteRatio: (Float) -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        VideoView(call, call.remoteSink, mirror = false, onRatio = onRemoteRatio)
        Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(120.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            VideoView(call, call.localSink, mirror = true, onTop = true)
        }
    }
}

/**
 * Vue vidéo WebRTC branchée sur un relais de [VideoCall].
 * `mirror = false` pour le flux distant : on regarde par une fenêtre, pas dans un miroir.
 *
 * SurfaceViewRenderer rogne l'image pour remplir la taille qu'on lui impose : on lui donne
 * donc exactement le ratio de la vidéo reçue (rotation comprise), la plus grande taille qui
 * tient dans l'espace disponible. Résultat : image entière, bandes noires si besoin.
 */
@Composable
fun VideoView(
    call: VideoCall,
    sink: VideoCall.ProxySink,
    mirror: Boolean,
    onTop: Boolean = false,
    onRatio: (Float) -> Unit = {},
) {
    var ratio by remember { mutableStateOf<Float?>(null) }
    AndroidView(
        modifier = ratio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                val view = this
                init(call.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}
                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        if (width == 0 || height == 0) return
                        val r = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
                        view.post { ratio = r; onRatio(r) } // appelé depuis le thread de rendu
                    }
                })
                setMirror(mirror)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                if (onTop) setZOrderMediaOverlay(true)
                sink.target = this
            }
        },
        onRelease = { view ->
            if (sink.target === view) sink.target = null
            view.release()
        }
    )
}
