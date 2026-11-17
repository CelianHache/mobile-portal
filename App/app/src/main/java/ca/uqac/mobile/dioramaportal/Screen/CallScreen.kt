package ca.uqac.mobile.dioramaportal.Screen

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ca.uqac.mobile.dioramaportal.Log.AppLog
import ca.uqac.mobile.dioramaportal.ViewModel.CallViewModel
import ca.uqac.mobile.dioramaportal.Log.LogList
import ca.uqac.mobile.dioramaportal.VideoCall
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Page d'appel : vidéo au ratio exact du flux reçu, et à côté (paysage) ou dessous (portrait)
 * le panneau avec le bouton « Mode portail » et le journal de l'appel en cours.
 */
@Composable
fun CallScreen(vm: CallViewModel, modifier: Modifier = Modifier) {
    val peer by vm.link.peer.collectAsState()
    val callLog by AppLog.call.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Ratio largeur/hauteur du flux reçu (connu à la première image). Gardé à la rotation.
    var remoteRatio by rememberSaveable { mutableStateOf<Float?>(null) }
    val onRatio = { r: Float -> remoteRatio = r }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("En appel avec ${peer.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.portalMode.value = true }) { Text("Mode portail") }
                    OutlinedButton(onClick = { vm.link.disconnect() }) { Text("Raccrocher") }
                }
                Text("Journal de l'appel", style = MaterialTheme.typography.labelLarge)
                LogList(
                    remember(callLog) { callLog.map { it.line } },
                    Modifier.weight(1f).fillMaxWidth()
                )
            }
        }

        if (landscape) {
            // Vidéo à gauche : toute la hauteur, largeur dictée par son ratio.
            // Le panneau prend toute la largeur restante (au moins MIN_PANEL).
            val maxVideoWidth = maxWidth - MIN_PANEL
            val videoWidth = remoteRatio?.let { minOf(maxHeight * it, maxVideoWidth) } ?: maxVideoWidth
            Row(Modifier.fillMaxSize()) {
                VideoArea(vm.call, Modifier.width(videoWidth).fillMaxHeight(), onRatio)
                panel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Vidéo en haut : toute la largeur, hauteur dictée par son ratio.
            // Le panneau prend toute la hauteur restante (au moins MIN_PANEL).
            val maxVideoHeight = maxHeight - MIN_PANEL
            val videoHeight = remoteRatio?.let { minOf(maxWidth / it, maxVideoHeight) } ?: maxVideoHeight
            Column(Modifier.fillMaxSize()) {
                VideoArea(vm.call, Modifier.fillMaxWidth().height(videoHeight), onRatio)
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Place minimale laissée au panneau à côté (ou sous) la vidéo. */
private val MIN_PANEL = 240.dp

/**
 * Mode portail du diorama : seulement la vidéo de l'autre lieu, sur fond noir, ratio conservé.
 * Appui long ou bouton retour pour revenir à la page d'appel.
 */
@Composable
fun PortalScreen(call: VideoCall, onExit: () -> Unit) {
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
                "Appui long pour quitter le mode portail",
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
