package com.darkp2p.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.darkp2p.app.R
import com.darkp2p.app.RoomViewModel
import com.darkp2p.app.Screen

@Composable
fun HomeScreen(vm: RoomViewModel, modifier: Modifier = Modifier) {
    // Not rememberSaveable: a pasted link holds a room key and must not be written to saved state.
    var link by remember { mutableStateOf("") }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyLarge)

        if (vm.waitingShares.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.home_shared_waiting, vm.waitingShares.size, vm.waitingShares.size), color = MaterialTheme.colorScheme.primary)
        }

        Button(onClick = vm::createRoom, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_new_room)) }

        Text(stringResource(R.string.home_join_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text(stringResource(R.string.home_link_hint)) },
            singleLine = true,
            // The link is a secret: no suggestions, no learning from it.
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = link.isNotBlank(),
                onClick = {
                    if (vm.open(link)) link = "" else vm.message = R.string.home_bad_link
                },
            ) { Text(stringResource(R.string.home_join)) }
            OutlinedButton(onClick = { vm.screen = Screen.SCANNER }) { Text(stringResource(R.string.home_scan)) }
        }

        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.screen = Screen.SETTINGS }) { Text(stringResource(R.string.home_settings)) }
            TextButton(onClick = {
                vm.refreshPro()
                vm.screen = Screen.PRO
            }) { Text(stringResource(R.string.home_pro)) }
        }
    }
}
