package com.darkp2p.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.darkp2p.app.DropState
import com.darkp2p.app.R
import com.darkp2p.app.RoomTexts
import com.darkp2p.app.RoomViewModel
import com.darkp2p.app.SensitiveClipboard
import com.darkp2p.transport.PlatformOnion

/** One file under a throwaway .onion link, for someone with Tor Browser. */
@Composable
fun DropScreen(vm: RoomViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.drop.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showQr by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.drop.start(uri) }
    // The ongoing notification is how a drop stays visible, and stoppable, outside the app.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (PlatformOnion.AVAILABLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.drop_title), style = MaterialTheme.typography.headlineSmall)

        if (!PlatformOnion.AVAILABLE) {
            Text(stringResource(R.string.drop_unavailable))
            return@Column
        }

        when (val s = state) {
            DropState.Idle -> {
                Text(stringResource(R.string.drop_text))
                Button(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.drop_choose)) }
            }

            is DropState.Starting -> {
                // At 100% Tor is running and is announcing the new address, which takes a little longer.
                Text(if (s.percent >= 100) stringResource(R.string.drop_publishing, s.name) else stringResource(R.string.drop_starting, s.name, s.percent))
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm.drop::stop) { Text(stringResource(R.string.cancel)) }
            }

            is DropState.Ready -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.drop_ready, s.name, RoomTexts.size(s.size)), style = MaterialTheme.typography.titleSmall)
                        Text(s.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                SensitiveClipboard.copy(context, scope, s.url)
                                vm.message = R.string.room_copied
                            }) { Text(stringResource(R.string.room_copy)) }
                            OutlinedButton(onClick = { showQr = !showQr }) {
                                Text(stringResource(if (showQr) R.string.room_hide_qr else R.string.room_show_qr))
                            }
                        }
                        if (showQr) QrImage(s.url)
                    }
                }
                Text(stringResource(R.string.drop_note), style = MaterialTheme.typography.bodySmall)
                if (s.sent > 0) {
                    LinearProgressIndicator(progress = { if (s.size > 0) (s.sent.toFloat() / s.size).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("${RoomTexts.size(s.sent)} / ${RoomTexts.size(s.size)}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = vm.drop::stop) { Text(stringResource(R.string.drop_stop)) }
            }

            is DropState.Done -> {
                Text(stringResource(R.string.drop_done, s.name))
                Button(onClick = vm.drop::stop) { Text(stringResource(R.string.drop_again)) }
            }

            is DropState.Failed -> {
                Text(stringResource(s.reason), color = MaterialTheme.colorScheme.error)
                Button(onClick = vm.drop::stop) { Text(stringResource(R.string.drop_again)) }
            }
        }
    }
}
