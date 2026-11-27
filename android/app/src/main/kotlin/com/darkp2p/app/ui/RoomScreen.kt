package com.darkp2p.app.ui

import android.Manifest
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.darkp2p.app.Prefs
import com.darkp2p.app.QrCodes
import com.darkp2p.app.R
import com.darkp2p.app.RoomTexts
import com.darkp2p.app.RoomViewModel
import com.darkp2p.app.Screen
import com.darkp2p.app.SensitiveClipboard
import com.darkp2p.protocol.RoomState
import com.darkp2p.service.RoomController
import com.darkp2p.service.SaveTarget

@Composable
fun RoomScreen(vm: RoomViewModel, room: RoomController, prefs: Prefs, modifier: Modifier = Modifier) {
    val snapshot by room.room.collectAsState()
    val queue by room.queue.collectAsState()
    val offer by room.offer.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = snapshot.state
    var showQr by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.send(uris) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) vm.accept(SaveTarget.Document(uri.toString()))
    }
    // The ongoing notification is how the room stays visible outside the app.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = { vm.screen = Screen.SETTINGS }) { Text(stringResource(R.string.home_settings)) }
                TextButton(onClick = vm::leave) { Text(stringResource(R.string.room_leave)) }
            }
        }

        // ---- Status ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(RoomTexts.state(state)),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (RoomTexts.isProblem(state)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (state is RoomState.Ready) {
                    Text(stringResource(RoomTexts.ip(state.ip)), style = MaterialTheme.typography.bodyMedium)
                    val cap = state.relayCap
                    val left = state.relayRemaining
                    if (cap != null && left != null) {
                        Text(stringResource(R.string.room_relay_left, RoomTexts.size(left), RoomTexts.size(cap)), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.hideIpViaRelay) Text(stringResource(R.string.room_hide_ip_relay_note), style = MaterialTheme.typography.bodySmall)
                }
                if (state == RoomState.RelayIdle) Button(onClick = room::resumeRelay) { Text(stringResource(R.string.room_resume)) }
                if (state is RoomState.Interrupted && state.canReconnect) Button(onClick = room::reconnect) { Text(stringResource(R.string.room_reconnect)) }
            }
        }

        // ---- The link, while nobody has joined ----
        val link = vm.link
        if (link != null && state !is RoomState.Ready) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.room_link_title), style = MaterialTheme.typography.titleSmall)
                    Text(link.shareUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.room_link_note), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            SensitiveClipboard.copy(context, scope, link.shareUrl)
                            vm.message = R.string.room_copied
                        }) { Text(stringResource(R.string.room_copy)) }
                        OutlinedButton(onClick = { showQr = !showQr }) {
                            Text(stringResource(if (showQr) R.string.room_hide_qr else R.string.room_show_qr))
                        }
                    }
                    if (showQr) QrImage(link.shareUrl)
                }
            }
        }

        // ---- Incoming offer ----
        offer?.let { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.room_offer_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.room_offer_text, o.name, RoomTexts.size(o.size)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.accept(SaveTarget.Downloads) }) { Text(stringResource(R.string.room_offer_save)) }
                        OutlinedButton(onClick = { saveAs.launch(o.name) }) { Text(stringResource(R.string.room_offer_save_as)) }
                    }
                    TextButton(onClick = room::decline) { Text(stringResource(R.string.room_offer_decline)) }
                }
            }
        }

        // ---- Transfers ----
        snapshot.incoming?.takeIf { it.phase != "offered" }?.let { t ->
            TransferRow(stringResource(R.string.room_receiving, com.darkp2p.service.FileNames.safe(t.name)), t.bytesDone, t.size, room::cancelReceive)
        }
        snapshot.outgoing?.let { t ->
            val label = if (t.phase == "offered") R.string.room_offering else R.string.room_sending
            TransferRow(stringResource(label, t.name), t.bytesDone, t.size, room::cancelSend)
        }
        if (queue.isNotEmpty()) Text(stringResource(R.string.room_queue, queue.joinToString(", ")), style = MaterialTheme.typography.bodySmall)

        // Sending is offered only once the other side's key is verified.
        Button(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = state is RoomState.Ready, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.room_send))
        }

        // ---- Privacy switches ----
        if (!prefs.tor) {
            SwitchRow(stringResource(R.string.room_force_relay), prefs.forceRelay) { on -> vm.applyPrefs { it.copy(forceRelay = on) } }
            SwitchRow(stringResource(R.string.room_hide_ip), prefs.hideIp) { on -> vm.applyPrefs { it.copy(hideIp = on) } }
        }

        // ---- Chat: only with another app that offers it ----
        if (state is RoomState.Ready && state.chat) ChatPanel(room)

        // ---- What happened ----
        for ((text, name) in vm.activity.takeLast(8)) {
            Text(if (name != null) stringResource(text, name) else stringResource(text), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransferRow(label: String, done: Long, size: Long, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text(stringResource(R.string.room_cancel)) }
        }
        LinearProgressIndicator(progress = { if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
        Text("${RoomTexts.size(done)} / ${RoomTexts.size(size)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** The link as a QR code: always dark on white, whatever the theme, so any scanner reads it. */
@Composable
private fun QrImage(text: String) {
    val bitmap = remember(text) {
        val matrix = QrCodes.encode(text)
        createBitmap(matrix.width, matrix.height).apply {
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) this[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }.asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.room_show_qr),
        // One pixel per module, scaled without smoothing so the edges stay sharp.
        filterQuality = FilterQuality.None,
        modifier = Modifier.size(260.dp).background(Color.White).padding(8.dp),
    )
}
