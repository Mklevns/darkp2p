package com.darkp2p.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.darkp2p.app.Prefs
import com.darkp2p.app.R
import com.darkp2p.app.RoomViewModel

@Composable
fun SettingsScreen(vm: RoomViewModel, prefs: Prefs, canLock: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SwitchRow(stringResource(R.string.settings_tor), prefs.tor) { on -> vm.applyPrefs { it.copy(tor = on) } }
        Text(stringResource(R.string.settings_tor_note), style = MaterialTheme.typography.bodySmall)

        SwitchRow(stringResource(R.string.settings_chat), prefs.chat) { on -> vm.applyPrefs { it.copy(chat = on) } }
        SwitchRow(stringResource(R.string.settings_secure), prefs.secure) { on -> vm.applyPrefs { it.copy(secure = on) } }

        SwitchRow(stringResource(R.string.settings_lock), prefs.lock && canLock, enabled = canLock) { on -> vm.applyPrefs { it.copy(lock = on) } }
        if (!canLock) Text(stringResource(R.string.settings_lock_unavailable), style = MaterialTheme.typography.bodySmall)
    }
}
