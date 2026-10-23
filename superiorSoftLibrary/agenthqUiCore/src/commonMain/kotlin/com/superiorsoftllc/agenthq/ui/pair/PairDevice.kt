package com.superiorsoftllc.agenthq.ui.pair

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost

/** The QR matrix of [text]: `true` is a dark module. Platform glue over ZXing's encoder; one encoder, so every client shows the same code. */
expect fun qrMatrix(text: String): Array<BooleanArray>

/** A platform modal over the app that holds the camera scanner; each platform owns its window API (none in commonMain). */
@Composable
expect fun ScannerModal(onDismissRequest: () -> Unit, content: @Composable () -> Unit)

const val PAIR_SHOW_TAG = "pair-show-qr"
const val PAIR_QR_TAG = "pair-qr"
const val PAIR_NOTICE_TAG = "pair-notice"
const val PAIR_PASTE_TAG = "pair-paste-field"
const val PAIR_CONNECT_TAG = "pair-paste-connect"
const val PAIR_SCAN_TAG = "pair-scan"
const val PAIR_IMAGE_TAG = "pair-open-image"
const val PAIR_ADDRESS_TAG = "pair-address"

/** Drawn with Compose on every platform: dark modules on a white square with the quiet zone the scanners need. */
@Composable
fun QrCodeView(text: String, modifier: Modifier = Modifier.size(220.dp)) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(modifier.background(Color.White)) {
        val quiet = 4
        val modules = matrix.size + 2 * quiet
        val cell = size.minDimension / modules
        for (y in matrix.indices) for (x in matrix[y].indices) {
            if (matrix[y][x]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** What the link of [rawUrl] / [token] is, or the notice that says why none can be shown. Pure; the section renders it. */
fun showableLink(rawUrl: String, token: String?, name: String?): PairingLink.Result =
    if (token.isNullOrBlank() && PairingLink.isLoopbackUrl(rawUrl)) {
        PairingLink.Result.Refused(
            "This server only accepts connections from this machine, so another device cannot reach it. " +
                "Turn on remote access (run `agenthq remote on`, then restart AgentHQ) and it will show a code here.",
        )
    } else {
        PairingLink.forShow(rawUrl, token, name)
    }

/**
 * PAIR-1: Settings > Pair a device. SHOW: a QR (behind a button, it holds the token) and a copy-link for the server this client
 * is connected to now. SCAN: the camera when the platform has one, an image file where it can decode one, and a pasted link
 * everywhere. [onLink] receives a valid scanned / pasted link; the caller saves and connects.
 */
@Composable
fun PairDeviceSection(connectedUrl: String, token: String?, serverName: String?, onLink: (PairingLink) -> Unit) {
    val host = LocalPlatformHost.current
    val options = remember(connectedUrl) { PairingHosts.reachableUrls(connectedUrl, host.localAddresses()) }
    var address by remember(options) { mutableStateOf(options.firstOrNull() ?: connectedUrl) }
    var showQr by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val shown = showableLink(address, token, serverName)

    fun accept(text: String) {
        when (val parsed = PairingLink.parse(text)) {
            is PairingLink.Parsed.Ok -> { error = null; paste = ""; onLink(parsed.link) }
            is PairingLink.Parsed.Bad -> error = parsed.reason
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Show", fontSize = 12.sp, color = Palette.Muted)
        if (options.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    val on = option == address
                    OutlinedButton(onClick = { address = option }) { Text(PairingHosts.labelOf(option) + if (on) "  ✓" else "", fontSize = 11.sp) }
                }
            }
        }
        OutlinedTextField(
            value = address, onValueChange = { address = it; showQr = false }, singleLine = true,
            label = { Text("Address other devices use") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag(PAIR_ADDRESS_TAG),
        )
        when (shown) {
            is PairingLink.Result.Refused -> Text(shown.reason, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag(PAIR_NOTICE_TAG))
            is PairingLink.Result.Ok -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showQr = !showQr }, modifier = Modifier.testTag(PAIR_SHOW_TAG)) {
                        Text(if (showQr) "Hide QR" else "Show QR")
                    }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(shown.link.toUri())) }) { Text("Copy link") }
                }
                if (showQr) {
                    QrCodeView(shown.link.toUri(), Modifier.size(220.dp).testTag(PAIR_QR_TAG))
                    Text("Anyone who scans this code gets access to this server. Hide it when done.", color = Palette.Muted, fontSize = 11.sp)
                }
            }
        }

        Text("Scan or paste", fontSize = 12.sp, color = Palette.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (host.qrScanner != null) {
                OutlinedButton(onClick = { scanning = true }, modifier = Modifier.testTag(PAIR_SCAN_TAG)) { Text("Scan QR") }
            }
            if (host.canPickFiles) {
                OutlinedButton(
                    onClick = {
                        val path = host.pickFile("Choose a picture of the QR code")
                        val bytes = path?.let(host::readBytes)
                        if (path != null) {
                            val decoded = bytes?.let(host::decodeQrImage)
                            if (decoded == null) error = "No QR code found in that picture." else accept(decoded)
                        }
                    },
                    modifier = Modifier.testTag(PAIR_IMAGE_TAG),
                ) { Text("Open image") }
            }
        }
        OutlinedTextField(
            value = paste, onValueChange = { paste = it; error = null }, singleLine = true,
            label = { Text("Paste agenthq://connect link") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag(PAIR_PASTE_TAG),
        )
        TextButton(onClick = { accept(paste) }, enabled = paste.isNotBlank(), modifier = Modifier.testTag(PAIR_CONNECT_TAG)) { Text("Connect with link") }
        error?.let { Text(it, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
    }

    val scanner = host.qrScanner
    if (scanning && scanner != null) {
        com.superiorsoftllc.agenthq.ui.RegisterModal()
        ScannerModal(onDismissRequest = { scanning = false }) {
            scanner(
                { code -> scanning = false; accept(code) },
                { scanning = false },
            )
        }
    }
}
