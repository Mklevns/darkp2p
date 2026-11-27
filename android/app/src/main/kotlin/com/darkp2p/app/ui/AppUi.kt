package com.darkp2p.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.darkp2p.app.Prefs
import com.darkp2p.app.R
import com.darkp2p.app.RoomViewModel
import com.darkp2p.app.Screen

private val Accent = Color(0xFF00C389)

@Composable
fun AppUi(vm: RoomViewModel, prefs: Prefs, locked: Boolean, onUnlock: () -> Unit, canLock: Boolean) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Accent) else lightColorScheme(primary = Color(0xFF00735A))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            if (locked) {
                // Nothing of the app is drawn behind the lock.
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.lock_unlock)) }
                }
                return@Surface
            }

            val snackbar = remember { SnackbarHostState() }
            val message = vm.message?.let { stringResource(it) }
            LaunchedEffect(message) {
                if (message != null) {
                    snackbar.showSnackbar(message)
                    vm.message = null
                }
            }
            BackHandler(enabled = vm.screen != Screen.HOME && vm.screen != Screen.ROOM) {
                if (vm.screen == Screen.DROP) vm.drop.stop()
                vm.screen = if (vm.controller.value != null) Screen.ROOM else Screen.HOME
            }

            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                val modifier = Modifier.padding(padding).fillMaxSize()
                val room by vm.controller.collectAsState()
                val back = { vm.screen = if (room != null) Screen.ROOM else Screen.HOME }
                // Leaving the drop screen takes the link down: nothing is shared out of sight.
                val leaveDrop = {
                    vm.drop.stop()
                    back()
                }
                when (vm.screen) {
                    Screen.HOME -> HomeScreen(vm, modifier)
                    Screen.ROOM -> room?.let { RoomScreen(vm, it, prefs, modifier) } ?: HomeScreen(vm, modifier)
                    Screen.SETTINGS -> SettingsScreen(vm, prefs, canLock, back, modifier)
                    Screen.PRO -> ProScreen(vm, back, modifier)
                    Screen.DROP -> DropScreen(vm, leaveDrop, modifier)
                    Screen.SCANNER -> ScannerScreen(
                        onResult = { text ->
                            vm.screen = Screen.HOME
                            if (!vm.open(text)) vm.message = R.string.home_bad_link
                        },
                        onBack = back,
                        modifier = modifier,
                    )
                }
            }

            vm.confirm?.let { link ->
                AlertDialog(
                    onDismissRequest = { vm.confirmJoin(false) },
                    title = { Text(stringResource(R.string.confirm_host_title)) },
                    text = { Text(stringResource(R.string.confirm_host_text, link.origin.substringAfter("://"))) },
                    confirmButton = { TextButton(onClick = { vm.confirmJoin(true) }) { Text(stringResource(R.string.confirm_host_ok)) } },
                    dismissButton = { TextButton(onClick = { vm.confirmJoin(false) }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}
