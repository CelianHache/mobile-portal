package ca.uqac.mobile.dioramaportal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Page principale (hors appel) : un onglet par mode de connexion, et la console de l'app
 * repliée en bas de l'écran.
 */
@Composable
fun ConnectScreen(vm: CallViewModel, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val status by vm.link.status.collectAsState()
    val connecting = status == PeerLink.Status.CONNECTING

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Réseau local") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Tailscale") })
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connecting) Text("Connexion en cours…", color = MaterialTheme.colorScheme.primary)
            when (tab) {
                0 -> LocalTab(vm.link, connecting)
                else -> TailscaleTab(vm.link, vm.tailscale, connecting)
            }
        }
        ConsolePanel()
    }
}

/** Onglet réseau local : même Wi-Fi ou point d'accès, connexion par adresse IP. */
@Composable
private fun LocalTab(link: PeerLink, connecting: Boolean) {
    val addresses = remember { PeerLink.localAddresses().filterNot { it.contains("(tun") } }
    Text("Les deux appareils doivent être sur le même Wi-Fi ou point d'accès.")
    SectionTitle("Cet appareil")
    if (addresses.isEmpty()) Text("Aucune adresse sur le réseau local.")
    addresses.forEach { Text(it, fontFamily = FontFamily.Monospace) }
    SectionTitle("Se connecter à un appareil")
    ManualConnect(link, connecting, label = "Adresse IP de l'autre appareil")
}

/** Onglet Tailscale : cet appareil, puis les autres appareils du réseau Tailscale. */
@Composable
private fun TailscaleTab(link: PeerLink, ts: TailscaleDirectory, connecting: Boolean) {
    val state by ts.state.collectAsState()
    val loaded = state as? TailscaleDirectory.State.Loaded

    SectionTitle("Cet appareil")
    val self = loaded?.devices?.firstOrNull { it.isSelf }
    val tunAddresses = remember { PeerLink.localAddresses().filter { it.contains("(tun") } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            when {
                self != null -> {
                    Text("${self.hostname}  (${self.os})")
                    Text(self.ip, fontFamily = FontFamily.Monospace)
                }
                tunAddresses.isNotEmpty() -> tunAddresses.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                else -> Text("Tailscale n'est pas actif sur cet appareil.")
            }
        }
    }

    SectionTitle("Appareils disponibles")
    when (val s = state) {
        TailscaleDirectory.State.NoKey -> ApiKeyForm(ts)
        TailscaleDirectory.State.Loading -> Text("Recherche des appareils…")
        is TailscaleDirectory.State.Error -> {
            Text("Erreur : ${s.message}", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ts.refresh() }) { Text("Réessayer") }
                OutlinedButton(onClick = { ts.forgetKey() }) { Text("Changer la clé") }
            }
        }
        is TailscaleDirectory.State.Loaded -> {
            val others = s.devices.filterNot { it.isSelf }
            if (others.isEmpty()) Text("Aucun autre appareil sur le réseau Tailscale.")
            others.forEach { d -> DeviceRow(d, enabled = !connecting) { link.connect(d.ip) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ts.refresh() }) { Text("Actualiser") }
                OutlinedButton(onClick = { ts.forgetKey() }) { Text("Changer la clé") }
            }
        }
    }

    SectionTitle("Ou saisir une adresse")
    ManualConnect(link, connecting, label = "IP Tailscale ou nom MagicDNS")
}

@Composable
private fun ApiKeyForm(ts: TailscaleDirectory) {
    var key by rememberSaveable { mutableStateOf("") }
    Text(
        "Pour lister les appareils, collez une clé d'API : console Tailscale → Settings → Keys → " +
            "Generate access token. Elle reste sur cet appareil.",
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

@Composable
private fun DeviceRow(d: TailscaleDirectory.Device, enabled: Boolean, onClick: () -> Unit) {
    val (dot, detail) = when {
        d.appOpen -> "●" to "app ouverte · appuyer pour appeler"
        d.online == false -> "○" to "hors ligne"
        else -> "○" to "app fermée"
    }
    val color = if (d.appOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("$dot ${d.hostname}  (${d.os})", color = color)
            Text("${d.ip} · $detail", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ManualConnect(link: PeerLink, connecting: Boolean, label: String) {
    var host by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { link.connect(host) }, enabled = host.isNotBlank() && !connecting) {
            Text("Se connecter")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/**
 * Console de l'app, repliée par défaut : une barre en bas montre la dernière ligne et un
 * bouton pour l'ouvrir. Ouverte, deux onglets (texte sélectionnable) :
 * - Session : depuis le lancement de l'app ;
 * - Historique : tout le fichier app.log, anciennes sessions comprises.
 */
@Composable
private fun ConsolePanel() {
    var open by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val entries by AppLog.all.collectAsState()
    val history by AppLog.history.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Console", style = MaterialTheme.typography.labelLarge)
            Text(
                if (open) "" else entries.lastOrNull()?.line.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { open = !open }) { Text(if (open) "Fermer ▼" else "Ouvrir ▲") }
        }
        if (open) {
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Session") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Historique") })
            }
            val lines = when (tab) {
                0 -> remember(entries) { entries.map { it.line } }
                else -> remember(history, entries) { history + entries.map { it.fileLine } }
            }
            if (tab == 1) {
                AppLog.filePath?.let {
                    Text(
                        "Fichier : $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    )
                }
            }
            LogList(
                lines,
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
