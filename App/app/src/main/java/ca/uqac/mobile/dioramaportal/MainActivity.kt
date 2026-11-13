package ca.uqac.mobile.dioramaportal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ca.uqac.mobile.dioramaportal.ui.theme.DioramaPortalTheme

class MainActivity : ComponentActivity() {

    private val link = PeerLink()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        link.startListening()
        requestLocalNetworkPermission()
        setContent {
            DioramaPortalTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConnectionScreen(link, Modifier.padding(innerPadding))
                }
            }
        }
    }

    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Sans l'accès au réseau local, seule la connexion via Tailscale fonctionne",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    /** Android 17 bloque le trafic vers le réseau local sans cette permission. */
    private fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < 37) return
        val perm = Manifest.permission.ACCESS_LOCAL_NETWORK
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            localNetworkPermission.launch(perm)
        }
    }

    override fun onDestroy() {
        link.close()
        super.onDestroy()
    }
}

@Composable
fun ConnectionScreen(link: PeerLink, modifier: Modifier = Modifier) {
    val status by link.status.collectAsState()
    val log by link.log.collectAsState()
    var host by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    val addresses = remember { PeerLink.localAddresses() }
    val listState = rememberLazyListState()

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("État : $status")
        Text("Mes adresses (port ${PeerLink.PORT}) :")
        addresses.forEach { Text("  $it", fontFamily = FontFamily.Monospace) }

        val connected = status == PeerLink.Status.CONNECTED
        if (!connected) {
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
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { link.send(message); message = "" }) { Text("Envoyer") }
            }
            Button(onClick = { link.disconnect() }) { Text("Déconnecter") }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(log) { Text(it, fontFamily = FontFamily.Monospace) }
        }
    }
}
