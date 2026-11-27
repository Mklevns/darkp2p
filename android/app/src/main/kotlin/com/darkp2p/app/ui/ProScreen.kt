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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.darkp2p.app.R
import com.darkp2p.app.RoomTexts
import com.darkp2p.app.RoomViewModel
import com.darkp2p.app.SensitiveClipboard
import com.darkp2p.transport.PendingClaim
import com.darkp2p.transport.ProTier
import java.text.DateFormat
import java.util.Date

@Composable
fun ProScreen(vm: RoomViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var paymentId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val secret = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.pro_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.pro_text))
        Text(stringResource(R.string.pro_server, vm.proOrigin.substringAfter("://")), style = MaterialTheme.typography.bodySmall)

        // ---- The saved token ----
        val saved = vm.savedToken
        val expiry = saved?.let(RoomTexts::tokenExpiry)
        Text(
            when {
                saved == null -> stringResource(R.string.pro_token_none)
                expiry != null && expiry * 1000 <= System.currentTimeMillis() -> stringResource(R.string.pro_token_expired)
                expiry != null -> stringResource(R.string.pro_token_active, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(expiry * 1000)))
                // Saved by a version that took any text: it can never work.
                else -> stringResource(R.string.pro_token_unusable)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (saved != null) OutlinedButton(onClick = vm::removeToken) { Text(stringResource(R.string.pro_token_remove)) }

        // ---- Buy ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.checkout(ProTier.DAY) }) { Text(stringResource(R.string.pro_buy_day)) }
            Button(onClick = { vm.checkout(ProTier.MONTH) }) { Text(stringResource(R.string.pro_buy_month)) }
        }
        vm.invoiceToCopy?.let { url ->
            OutlinedButton(onClick = { SensitiveClipboard.copy(context, scope, url) }) { Text(stringResource(R.string.pro_copy_invoice)) }
        }

        // ---- A payment being checked ----
        if (vm.pendingClaim != null) {
            Text(stringResource(R.string.pro_pending), color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.pro_payment_id_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = paymentId,
                onValueChange = { paymentId = it.filter(Char::isDigit).take(20) },
                label = { Text(stringResource(R.string.pro_payment_id_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = PendingClaim.isNowPaymentsId(paymentId),
                onClick = {
                    vm.onPaymentId(paymentId)
                    paymentId = ""
                },
            ) { Text(stringResource(R.string.pro_payment_id_submit)) }
        }

        // ---- A token bought elsewhere (the web page shows it in its Pro dialog) ----
        Text(stringResource(R.string.pro_token_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.take(128) },
            label = { Text(stringResource(R.string.pro_token_hint)) },
            singleLine = true,
            keyboardOptions = secret,
            isError = token.isNotBlank() && !RoomTexts.isTokenShaped(token),
            supportingText = {
                if (token.isNotBlank() && !RoomTexts.isTokenShaped(token)) Text(stringResource(R.string.pro_token_bad))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = RoomTexts.isTokenShaped(token),
            onClick = {
                vm.saveToken(token)
                token = ""
            },
        ) { Text(stringResource(R.string.pro_token_save)) }
    }
}
