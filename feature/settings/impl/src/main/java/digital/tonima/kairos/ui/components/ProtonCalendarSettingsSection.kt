package digital.tonima.kairos.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import digital.tonima.core.viewmodel.LoginUiState
import digital.tonima.core.viewmodel.ProtonSyncSettingsViewModel
import digital.tonima.kairos.core.ui.theme.Dimensions
import digital.tonima.kairos.feature.settings.impl.R
import digital.tonima.protonsync.session.SessionState
import digital.tonima.protonsync.status.ProtonSyncUiState
import digital.tonima.protonsync.status.SyncState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The "Calendar sync" settings section (architecture §1 item 4, §8): self-contained, owns
 * its ViewModel, renders the status card for every documented state — Disconnected /
 * Connected · Last sync / Syncing… / Error / Session expired — plus the sync-frequency
 * selector and the Disconnect path. Inserted by the app's SettingsScreen under the
 * skip-weekends/all-day sections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtonCalendarSettingsSection() {
    val viewModel: ProtonSyncSettingsViewModel = hiltViewModel()
    val status by viewModel.status.collectAsState()
    val login by viewModel.loginState.collectAsState()

    HorizontalDivider(modifier = Modifier.padding(vertical = Dimensions.PaddingTiny))
    Text(
        text = stringResource(R.string.proton_sync_section_title),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when {
        login.showingLogin -> ProtonLoginForm(viewModel, login)
        status.sessionState == SessionState.LOGGED_OUT ->
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpacingSmall)) {
                Text(
                    text = stringResource(R.string.proton_sync_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                OutlinedButton(onClick = viewModel::openLogin) {
                    Text(stringResource(R.string.proton_sync_connect))
                }
            }
        else -> ProtonStatusCard(viewModel, status)
    }
}

@Composable
private fun ProtonStatusCard(
    viewModel: ProtonSyncSettingsViewModel,
    status: ProtonSyncUiState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpacingSmall)) {
        status.accountEmail?.let { email ->
            Text(
                text = stringResource(R.string.proton_sync_connected, email),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        when (status.syncState) {
            SyncState.SYNCING -> Text(
                text = stringResource(R.string.proton_sync_syncing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SyncState.ERROR -> Text(
                text = stringResource(R.string.proton_sync_error, status.lastError.orEmpty()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            SyncState.NEEDS_REAUTH -> Text(
                text = stringResource(R.string.proton_sync_session_expired),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            SyncState.IDLE -> status.lastSyncEpochMs?.let { lastSync ->
                Text(
                    text = stringResource(
                        R.string.proton_sync_last_sync,
                        DateTimeFormatter.ofPattern("HH:mm")
                            .format(Instant.ofEpochMilli(lastSync).atZone(ZoneId.systemDefault())),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(
                R.string.proton_sync_counters,
                status.counters.eventsSynced,
                status.counters.eventsDeleted,
                status.counters.decryptFailures,
                status.counters.verifyFailures,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SyncFrequencySelector(viewModel)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.SpacingSmall)) {
            Button(onClick = viewModel::syncNow) {
                Text(stringResource(R.string.proton_sync_sync_now))
            }
            OutlinedButton(onClick = viewModel::logout) {
                Text(stringResource(R.string.proton_sync_disconnect))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncFrequencySelector(viewModel: ProtonSyncSettingsViewModel) {
    val intervals = listOf(1, 6, 12, 24)
    var expanded by remember { mutableStateOf(false) }
    val labels =
        mapOf(
            1 to stringResource(R.string.proton_sync_frequency_1h),
            6 to stringResource(R.string.proton_sync_frequency_6h),
            12 to stringResource(R.string.proton_sync_frequency_12h),
            24 to stringResource(R.string.proton_sync_frequency_24h),
        )
    var selectedHours by remember { mutableStateOf(6) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = labels[selectedHours] ?: labels.getValue(6),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.proton_sync_frequency_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            intervals.forEach { hours ->
                DropdownMenuItem(
                    text = { Text(labels[hours] ?: hours.toString()) },
                    onClick = {
                        selectedHours = hours
                        viewModel.setSyncInterval(hours)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ProtonLoginForm(
    viewModel: ProtonSyncSettingsViewModel,
    login: LoginUiState,
) {
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // FLAG_SECURE while credential fields are visible (security-audit §2.5.20).
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpacingSmall)) {
        Text(
            text = stringResource(R.string.proton_sync_login_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = login.email,
            onValueChange = viewModel::onEmailChanged,
            label = { Text(stringResource(R.string.proton_sync_email_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.proton_sync_password_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { password() },
        )
        if (login.requiresTwoFactor) {
            OutlinedTextField(
                value = login.twoFactorCode,
                onValueChange = viewModel::onTwoFactorCodeChanged,
                label = { Text(stringResource(R.string.proton_sync_two_factor_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (login.requiresMailboxPassword) {
            OutlinedTextField(
                value = login.mailboxPassword,
                onValueChange = viewModel::onMailboxPasswordChanged,
                label = { Text(stringResource(R.string.proton_sync_mailbox_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Consent gate (security-audit HI-2 / ME-5): before the FIRST connect the user must
        // acknowledge that Proton events become device-readable.
        if (!login.consentGranted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = login.consentChecked,
                    onCheckedChange = viewModel::onConsentChanged,
                )
                Text(
                    text = stringResource(R.string.proton_sync_consent_text),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        login.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val consentOk = login.consentGranted || login.consentChecked
        val credentialsOk = login.email.isNotBlank() && password.isNotBlank()
        Button(
            onClick = {
                when {
                    login.requiresTwoFactor -> viewModel.submitTwoFactor()
                    login.requiresMailboxPassword -> viewModel.submitMailboxPassword()
                    else -> viewModel.submitLogin(password)
                }
            },
            enabled = !login.inFlight &&
                (login.requiresTwoFactor || login.requiresMailboxPassword || (consentOk && credentialsOk)),
        ) {
            Text(stringResource(R.string.proton_sync_continue))
        }
        TextButton(onClick = viewModel::dismissLogin) {
            Text(stringResource(R.string.proton_sync_cancel))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
