package com.lumenarcanus.dialife.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.lumenarcanus.dialife.ConnectFailure
import com.lumenarcanus.dialife.ConnectUiState
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.ui.theme.DataLabel
import com.lumenarcanus.dialife.ui.theme.DiaTheme

@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onScanned: (String) -> Unit,
    onScannerFailed: () -> Unit,
    onManual: (server: String, token: String) -> Unit,
) {
    val c = DiaTheme.colors
    val context = LocalContext.current
    var manual by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("https://") }
    var token by remember { mutableStateOf("") } // never saved into instance state

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let(onScanned) }
            .addOnFailureListener { onScannerFailed() }
        // a cancelled scan reports neither: nothing to do
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineMedium, color = c.text)
        Text(stringResource(R.string.connect_body), color = c.textMuted, fontSize = 15.sp, lineHeight = 22.sp)

        Button(
            onClick = ::scan,
            enabled = !state.checking,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.inRange, contentColor = c.background),
        ) {
            Text(stringResource(R.string.connect_scan), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(20.dp))
                .border(1.dp, c.line, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.connect_cli_hint), color = c.textMuted, fontSize = 13.sp)
            Text(
                stringResource(R.string.connect_cli_command),
                style = DataLabel.copy(fontSize = 14.sp),
                color = c.text,
                modifier = Modifier.fillMaxWidth().background(c.background, RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }

        Status(state)

        OutlinedButton(
            onClick = { manual = !manual },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(stringResource(R.string.connect_manual), color = c.text, fontSize = 15.sp)
        }

        AnimatedVisibility(manual) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.text, unfocusedTextColor = c.text,
                    focusedBorderColor = c.inRange, unfocusedBorderColor = c.line,
                    focusedLabelColor = c.inRange, unfocusedLabelColor = c.textMuted, cursorColor = c.inRange,
                )
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text(stringResource(R.string.connect_server)) },
                    singleLine = true, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(stringResource(R.string.connect_token)) },
                    singleLine = true, colors = fieldColors,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onManual(server, token) },
                    enabled = !state.checking,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.surfaceRaised, contentColor = c.text),
                ) {
                    Text(stringResource(R.string.connect_button), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Status(state: ConnectUiState) {
    val c = DiaTheme.colors
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite } // TalkBack reads changes
    when {
        state.checking -> Row(live, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = c.inRange, strokeWidth = 2.dp)
            Text(stringResource(R.string.connect_checking), color = c.textMuted, fontSize = 15.sp)
        }
        state.failure != null -> Text(
            failureText(state),
            color = c.low,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = live.fillMaxWidth().background(c.lowContainer, RoundedCornerShape(14.dp)).padding(14.dp),
        )
    }
}

@Composable
private fun failureText(state: ConnectUiState): String = when (state.failure) {
    ConnectFailure.NotDiaLife -> stringResource(R.string.err_link_not_dialife)
    ConnectFailure.BadServer -> stringResource(R.string.err_link_server)
    ConnectFailure.BadToken -> stringResource(R.string.err_link_token)
    ConnectFailure.Network -> stringResource(R.string.err_network)
    ConnectFailure.Unauthorized -> stringResource(R.string.err_unauthorized)
    ConnectFailure.Server -> stringResource(R.string.err_server, state.serverCode ?: 0)
    ConnectFailure.UploaderToken -> stringResource(R.string.err_uploader)
    ConnectFailure.Scanner -> stringResource(R.string.err_scanner)
    ConnectFailure.Revoked -> stringResource(R.string.err_revoked)
    null -> ""
}
