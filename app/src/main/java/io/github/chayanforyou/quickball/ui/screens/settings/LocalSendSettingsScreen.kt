package io.github.chayanforyou.quickball.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.localsend.LocalSendClient
import io.github.chayanforyou.quickball.localsend.LocalSendResult
import io.github.chayanforyou.quickball.localsend.LocalSendSender
import io.github.chayanforyou.quickball.localsend.LocalSendTarget
import io.github.chayanforyou.quickball.ui.theme.AppCardDefaults

/**
 * One-time setup of the LocalSend receiver used by the "Send clipboard to LocalSend" action.
 * "Detect" reads the device name and pins the certificate it presents (trust on first use).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSendSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { AppPreference.getInstance(context) }
    val saved = remember { prefs.localSendTarget }

    var host by rememberSaveable { mutableStateOf(saved.host) }
    var port by rememberSaveable { mutableStateOf(saved.port.toString()) }
    var https by rememberSaveable { mutableStateOf(saved.https) }
    var pin by rememberSaveable { mutableStateOf(saved.pin) }
    var fingerprint by rememberSaveable { mutableStateOf(saved.fingerprint) }
    var alias by rememberSaveable { mutableStateOf(saved.alias) }
    var status by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun currentTarget() = LocalSendTarget(
        host = host.trim(),
        port = port.trim().toIntOrNull() ?: -1,
        https = https,
        fingerprint = fingerprint.trim(),
        pin = pin,
        alias = alias.trim()
    )

    fun saveIfValid(): LocalSendTarget? {
        val target = currentTarget()
        if (!target.isConfigured) {
            status = context.getString(R.string.localsend_invalid_target)
            return null
        }
        prefs.localSendTarget = target
        return target
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.localsend_settings_title),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.menu_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.localsend_settings_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = AppCardDefaults.cardColors()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.localsend_host_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.localsend_port_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.localsend_https_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.localsend_https_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = https, onCheckedChange = { https = it })
                    }

                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text(stringResource(R.string.localsend_pin_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text(stringResource(R.string.localsend_alias_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (https) {
                        OutlinedTextField(
                            value = fingerprint,
                            onValueChange = { fingerprint = it },
                            label = { Text(stringResource(R.string.localsend_fingerprint_label)) },
                            supportingText = { Text(stringResource(R.string.localsend_fingerprint_hint)) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // Detect without a pin so a new certificate (e.g. after reinstalling
                        // LocalSend) is picked up; the result replaces the stored fingerprint.
                        val target = currentTarget().copy(fingerprint = "")
                        if (!target.isConfigured) {
                            status = context.getString(R.string.localsend_invalid_target)
                            return@OutlinedButton
                        }
                        busy = true
                        status = context.getString(R.string.localsend_detecting)
                        LocalSendSender.runAsync(
                            block = { runCatching { LocalSendClient(context.applicationContext).fetchInfo(target) } },
                            onResult = { result ->
                                busy = false
                                result.onSuccess { info ->
                                    if (info.alias.isNotBlank()) alias = info.alias
                                    if (https) fingerprint = info.fingerprint
                                    saveIfValid()
                                    status = context.getString(R.string.localsend_detected, info.alias)
                                }.onFailure {
                                    status = context.getString(
                                        R.string.localsend_unreachable,
                                        "${target.host}:${target.port}"
                                    )
                                }
                            }
                        )
                    }
                ) {
                    Text(stringResource(R.string.localsend_detect))
                }

                Button(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (saveIfValid() != null) {
                            status = context.getString(R.string.localsend_saved)
                        }
                    }
                ) {
                    Text(stringResource(R.string.localsend_save))
                }
            }

            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target = saveIfValid() ?: return@OutlinedButton
                    busy = true
                    status = context.getString(R.string.localsend_test_waiting)
                    val message = context.getString(R.string.localsend_test_message)
                    LocalSendSender.runAsync(
                        block = { LocalSendClient(context.applicationContext).sendText(target, message) },
                        onResult = { result ->
                            busy = false
                            status = if (result == LocalSendResult.Delivered) {
                                context.getString(R.string.localsend_test_ok)
                            } else {
                                LocalSendSender.describe(context, result, target)
                            }
                        }
                    )
                }
            ) {
                Text(stringResource(R.string.localsend_test))
            }

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
        }
    }
}
