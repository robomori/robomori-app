package com.robomori

import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.*
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

/** Settings → Network: the computer's ports that apps on every device reach as localhost. */
@Composable
internal fun NetworkPage(ports: LocalhostPortsState) {
    val field = rememberTextFieldState()
    fun add() { if (ports.add(field.text.toString())) field.clearText() }
    SettingsPageScaffold(SettingsSection.Network.title, "NetworkPage") {
        Card(contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsCardTitle("Port Forwarding")
                SettingsNote("Robomori forwards this computer's localhost ports to your Android devices, so they can reach the same ports at their localhost.")
            }
            Spacer(Modifier.height(12.dp))
            Text("Ports", style = RobomoriTheme.typography.heading)
            Spacer(Modifier.height(6.dp))
            ChipField(ports.ports.map(Int::toString), { ports.remove(it.toInt()) }, { "Remove port $it" }, field, { add() },
                placeholder = "Add a port", tag = "LocalhostPorts")
            Spacer(Modifier.height(8.dp))
            // Always laid out, so an error doesn't move the page.
            Label(if (ports.invalidPort) "Enter a port from 1 to 65535, such as 3000." else "", color = RobomoriTheme.colors.danger, modifier = Modifier.testTag("LocalhostPortError"))
        }
        PlainHttpCard()
    }
}

/** Android blocks http:// unless an app allows it, which is where forwarding to a local dev server usually trips. */
@Composable
private fun PlainHttpCard() {
    Card(Modifier.testTag("PlainHttpCard"), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsCardTitle("Plain HTTP")
            SettingsNote("Android blocks http:// connections unless your app allows them. To allow them to localhost in debug builds, add this file to your app:")
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer { SettingsNote("app/src/debug/res/xml/network_security_config.xml") }
        Spacer(Modifier.height(6.dp))
        CodeBlock(NetworkSecurityConfig, language = CodeLanguage.Xml, tag = "NetworkSecurityConfig")
        Spacer(Modifier.height(12.dp))
        SettingsNote("Then point to it from app/src/debug/AndroidManifest.xml:")
        Spacer(Modifier.height(6.dp))
        CodeBlock("<application android:networkSecurityConfig=\"@xml/network_security_config\" />", language = CodeLanguage.Xml, tag = "NetworkSecurityConfigManifest")
    }
}

private const val NetworkSecurityConfig = """<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>"""
