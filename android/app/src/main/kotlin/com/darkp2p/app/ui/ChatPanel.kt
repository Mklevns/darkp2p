package com.darkp2p.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darkp2p.app.R
import com.darkp2p.protocol.ChatConstants
import com.darkp2p.service.ChatMessage
import com.darkp2p.service.RoomController

/** The conversation with the other device. Shown only when both sides are the app and both offer chat. */
@Composable
fun ChatPanel(room: RoomController) {
    val messages by room.chat.collectAsState()
    // Not saved across process death: messages live only as long as the room.
    var draft by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.chat_note), style = MaterialTheme.typography.bodySmall)
            for (m in messages.takeLast(50)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                    Text(m.text, textAlign = if (m.mine) TextAlign.End else TextAlign.Start)
                    val status = when (m.status) {
                        ChatMessage.Status.SENDING -> R.string.chat_sending
                        ChatMessage.Status.DELIVERED -> R.string.chat_delivered
                        ChatMessage.Status.FAILED -> R.string.chat_failed
                        ChatMessage.Status.RECEIVED -> null
                    }
                    if (status != null) {
                        Text(
                            stringResource(status),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (m.status == ChatMessage.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.toByteArray().size <= ChatConstants.MAX_TEXT_BYTES) draft = it },
                    label = { Text(stringResource(R.string.chat_hint)) },
                    keyboardOptions = KeyboardOptions.Default,
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        room.sendChat(draft.trim())
                        draft = ""
                    },
                ) { Text(stringResource(R.string.chat_send)) }
            }
        }
    }
}
