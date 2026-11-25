package com.robomori

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.*
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

private enum class PairingMethod { QrCode, Code }

@Composable
fun ConnectDeviceDialog(state: ConnectedDevicesState) {
    var method by remember { mutableStateOf<PairingMethod?>(null) }
    var qr by remember { mutableStateOf(QrPairing.create()) }
    var selected by remember { mutableStateOf<WirelessDevice?>(null) }
    LaunchedEffect(state, method, qr) {
        if (method == null) return@LaunchedEffect
        state.observePairing(if (method == PairingMethod.QrCode) qr else null)
    }
    LaunchedEffect(state.nearby) {
        if (selected !in state.nearby) selected = state.nearby.singleOrNull()
    }
    fun close() { state.cancel(); state.showConnect = false }
    val title = when (method) {
        PairingMethod.QrCode -> "Pair device with QR Code"
        PairingMethod.Code -> "Pair with pairing code"
        null -> "Pair device"
    }
    AppDialogPanel(title, "ConnectDeviceDialog", Modifier.width(520.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (method != null && state.pairing !is OperationState.Failed) {
                    Button(onClick = {
                        state.openPairing()
                        selected = null
                        method = null
                    }, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Back to pairing methods", tag = "BackToPairingMethods", enabled = !state.working) { Icon(Icons.ArrowLeft) }
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = RobomoriTheme.typography.heading)
                Spacer(Modifier.weight(1f))
                Button(onClick = ::close, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Close pairing", tag = "ClosePairing") { Icon(Icons.XMark) }
            }
            Rule()
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (method == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PairingMethodButton("Pair device with QR Code", Icons.QrCode,
                            !state.working, Modifier.weight(1f).testTag("PairWithQrCode")) {
                            state.openPairing()
                            qr = QrPairing.create()
                            method = PairingMethod.QrCode
                        }
                        PairingMethodButton("Pair with pairing code", Icons.Key,
                            !state.working, Modifier.weight(1f).testTag("PairWithPairingCode")) {
                            state.openPairing()
                            selected = null
                            method = PairingMethod.Code
                        }
                    }
                } else if (state.pairing is OperationState.Failed) {
                    Box(Modifier.fillMaxWidth().height(216.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            DeviceStateMessage("Could not pair with your device.")
                            Button(onClick = {
                                state.openPairing()
                                selected = null
                                method = null
                            }, style = ButtonStyle.Secondary, tag = "Try again") { Label("Try again") }
                        }
                    }
                } else if (method == PairingMethod.QrCode) {
                    DeviceStateMessage(buildAnnotatedString {
                        append("Enable ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Wireless debugging") }
                        append(" on your device, select ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Pair device with QR code") }
                        append(" and scan this code.")
                    })
                    Box(Modifier.size(216.dp).testTag("QrPairingContent"), contentAlignment = Alignment.Center) {
                        if (state.working) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                DeviceStateMessage("Pairing…")
                                Spinner(Modifier.size(24.dp).testTag("QrPairingSpinner").semantics {
                                    contentDescription = "Pairing device"
                                })
                            }
                        } else {
                            PairingQrCode(qr, Modifier.fillMaxSize().testTag("PairingQrCode"))
                        }
                    }
                    if (state.discovery is OperationState.Failed) DeviceStateMessage("Could not search for devices. Check your Wi-Fi connection.")
                } else if (state.nearby.isEmpty()) {
                    DeviceStateMessage(buildAnnotatedString {
                        append("Enable ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Wireless debugging") }
                        append(" on your device and select ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Pair device with pairing code") }
                        append(" to continue.")
                    })
                    Card(contentPadding = PaddingValues(20.dp)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DeviceStateMessage("Searching…")
                            Spinner(Modifier.size(24.dp).testTag("SearchingForDevicesSpinner").semantics {
                                contentDescription = "Searching for devices"
                            })
                        }
                    }
                    if (state.discovery is OperationState.Failed) DeviceStateMessage("Could not search for devices. Check your Wi-Fi connection.")
                } else {
                    if (state.nearby.size > 1) {
                        Label("Choose your device’s pairing address")
                        state.nearby.forEach { device ->
                            Button(onClick = { selected = device }, style = if (selected == device) ButtonStyle.Primary else ButtonStyle.Secondary, tag = device.address, enabled = !state.working) { Label(device.address) }
                        }
                    }
                    DeviceStateMessage("Enter the six-digit pairing code shown on your device.")
                    key(selected) {
                        PairingCodeField(enabled = !state.working && selected != null) { code ->
                            if (code.length == 6 && !state.working) {
                                selected?.takeIf { it in state.nearby }?.let { state.pair(it, code) }
                            }
                        }
                    }
                }
                if (state.working && method != PairingMethod.QrCode) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spinner(Modifier.size(16.dp))
                        DeviceStateMessage("Pairing…")
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingMethodButton(label: String, icon: ImageVector, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, style = ButtonStyle.Outlined, modifier = modifier.heightIn(min = 92.dp), enabled = enabled, tag = label) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, modifier = Modifier.size(24.dp))
            Text(label, textAlign = TextAlign.Center)
        }
    }
}
